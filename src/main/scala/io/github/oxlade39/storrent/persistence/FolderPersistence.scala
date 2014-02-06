package io.github.oxlade39.storrent.persistence

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import io.github.oxlade39.storrent.core.{TorrentFile, Torrent}
import akka.event.LoggingReceive
import java.io.{RandomAccessFile, File}
import io.github.oxlade39.storrent.peer.DownloadPiece
import akka.util.ByteString
import org.apache.commons.io.FileUtils
import io.github.oxlade39.storrent.persistence.FolderPersistence.FileOffset
import scala.collection.SortedSet

object Persistence {
  case class Persist(piece: DownloadPiece)
}

object FolderPersistence {
  case class FileOffset(startOffset: Long, endOffset: Long) {
    def size = endOffset - startOffset

    def chop(piece: DownloadPiece): ByteString = {
      val bytes = piece.contiguousStream.get

      if (piece.offset > startOffset)
        bytes.slice(0, endOffset.toInt - piece.offset)
      else {
        val begin = startOffset.toInt - piece.offset
        bytes.slice(begin.toInt, endOffset.toInt)
      }
    }
  }
  case class Done(directory: File)

  def props(torrent: Torrent, directory: File) =
    Props(new FolderPersistence(torrent: Torrent, directory: File))
}

class FolderPersistence(torrent: Torrent, directory: File) extends Actor with ActorLogging {
  import Persistence._
  import FolderPersistence._

  if (!directory.exists()) {
    log.info("{} does not exist, created: {}", directory.getAbsolutePath, directory.mkdir())
  }

  var subfiles: List[(FileOffset, ActorRef)] = {
    val offset = FileOffset(0, torrent.files.head.size)
    torrent.files.tail.foldLeft(List(offset -> newFilePersistence(torrent.files.head, offset))) {
      (accum, file) ⇒
        val nextOffset = FileOffset(accum.head._1.endOffset, accum.head._1.endOffset + file.size)
        (nextOffset -> newFilePersistence(file, nextOffset)) :: accum
    }.reverse
  }


  def newFilePersistence(f: TorrentFile, offset: FileOffset) =
    context.actorOf(FilePersistence.props(toPersistInto = new File(directory, f.name), fileOffset = offset), f.name)

  def receive = LoggingReceive {
    case FilePersistence.Done(file) =>
      val child = sender
      subfiles = subfiles.filterNot{ case (offset, actor) => actor == child }
      context.stop(child)
      if (subfiles.isEmpty) {
        log.info("Finished persisting all files")
        context.parent ! Done(directory)
        context.stop(self)
      }
    
    case Persist(p) => {
      val pieceStart = p.offset
      val pieceSize = p.size
      val pieceEnd = pieceStart + (pieceSize - 1)
      val pieceBytes = p.contiguousStream.get

      val toSendTo: List[(FileOffset, ActorRef)] =
        subfiles.filter {
          case (offset, _) =>
            (pieceStart >= offset.startOffset && pieceStart < offset.endOffset) ||
              (pieceEnd >= offset.startOffset && pieceEnd < offset.endOffset)
        }

      log.debug("piece {} from {} to {} forms part of files {}", p.index, pieceStart, pieceEnd, toSendTo)

      val bytesToSave: List[(ActorRef, FilePersistence.Write)] =
        toSendTo.foldLeft(List.empty[(ActorRef, FilePersistence.Write)]) {
          case (accum, (offset, actorRef)) ⇒ {

            val FileOffset(offsetStart, offsetEnd) = offset

            val bytes = offset.chop(p)
            val startWithinOffset =
              if (pieceStart > offsetStart)
                (offsetEnd - (offsetEnd - pieceStart)) - offsetStart
              else 0

            (actorRef, FilePersistence.Write(bytes, startWithinOffset)) :: accum
          }
        }
      bytesToSave.foreach{ case (actor, command) ⇒ actor ! command }
    }
  }

}

object FilePersistence {
  case class Write(block: ByteString, offset: Long)
  case class Done(file: File)
  val PARTIAL_FILE_SUFFIX = ".part"

  def props(toPersistInto: File,
            partialFileSuffix: String = "part",
            fileOffset: FileOffset) = Props(new FilePersistence(toPersistInto, partialFileSuffix, fileOffset))
}

class FilePersistence(targetFile: File,
                      partialFileSuffix: String,
                      fileOffset: FileOffset) extends Actor with ActorLogging {
  import FilePersistence._

  var written = SortedSet.empty[FileOffset](Ordering.fromLessThan((a, b) => a.startOffset < b.startOffset))

  // TODO this could be compacted on ReceiveTimeout messages
  def isCompleted: Boolean = written.foldLeft[Option[FileOffset]](Some(FileOffset(0, 0))){(accum, offset) => accum match {
    case Some(FileOffset(accumStart, accumEnd)) if accumEnd >= offset.startOffset =>
       Some(FileOffset(accumStart, offset.endOffset.max(accumEnd)))
    case _ => Option.empty[FileOffset]
  }} match {
    case Some(offset) => offset.size == fileOffset.size
    case _ => false
  }

  val (output, channel, partial) = {
    val p = new File(targetFile.getAbsolutePath + "." + partialFileSuffix)
    
    if (!p.exists()) {
      log.info("{} does not exist yet", p)
      FileUtils.forceMkdir(p.getParentFile)
      assert(p.createNewFile(), s"couldn't create $p")
      if (!p.getParentFile.exists()) {
        log.info("{} does not exist so attempting to create: {}",
          p.getParentFile.getAbsolutePath, p.getParentFile.mkdirs())
      }
    }
    val raf = new RandomAccessFile(p, "rw")
    raf.setLength(fileOffset.size)
    (raf, raf.getChannel, p)
  }

  def receive = LoggingReceive {
    case _ if isCompleted => // ignore
    case Write(block, blockOffset) =>
      log.debug("{} writing to position {}", targetFile, blockOffset)
      channel.write(block.asByteBuffer, blockOffset)
      written += FileOffset(blockOffset, blockOffset + block.size)
      if (isCompleted) {
        log.info("{} should now be complete, writing out", targetFile)
        channel.force(true)
        output.close()
        FileUtils.deleteQuietly(targetFile)
        FileUtils.moveFile(partial, targetFile)
        FileUtils.deleteQuietly(partial)
        log.info("partial removed for {} and file completed. Sending Done", targetFile)
        context.parent ! Done(targetFile)
      }
  }
}