package io.github.oxlade39.storrent.persistence

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.{ForwardingParent, FileOps}
import java.io.File
import scala.util.Random
import akka.util.ByteString
import scala.io.Source
import org.scalatest.mock.MockitoSugar
import io.github.oxlade39.storrent.core.{TorrentFile, Torrent}
import io.github.oxlade39.storrent.peer.{Block, DownloadPiece}
import org.mockito.Mockito
import org.apache.commons.io.FileUtils

class FilePersistenceTest extends TestKit(ActorSystem("FilePersistenceTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps {

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "FilePersistence" must {
    "write a file to disk" in {
      val testOut = new File("target") / "testOut" / s"FilePersistenceTest${Random.nextLong()}"
      testOut.delete()

      val fileContent =
        ByteString("""
          |Lorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsum
          |This is the body of a test file which
          |
          |should be broken up into pieces
          |
          |and writtin to file
        """.stripMargin)

      val underTest = system.actorOf(Props(new ForwardingParent(
        FilePersistence.props(toPersistInto = testOut, fileOffset = FolderPersistence.FileOffset(0, fileContent.size)),
        testActor)))

      val writes = for {
        (block, offsetIndex) <- Random.shuffle(fileContent.grouped(10).zipWithIndex)
      } yield FilePersistence.Write(block, offsetIndex * 10)

      writes foreach (underTest ! _)
      val done = expectMsgType[FilePersistence.Done]

      val fileAsString = Source.fromFile(done.file).getLines().mkString("\n")
      val rawBytesAsString = fileContent.utf8String
      fileAsString mustEqual rawBytesAsString
    }

    "not complete prematurely when sent duplicate pieces" in {
      val testOut = new File("target") / "testOut" / s"FilePersistenceTest${Random.nextLong()}"
      testOut.delete()

      val fileContent =
        ByteString("""
                     |Lorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsumLorum ipsum
                     |This is the body of a test file which
                     |
                     |should be broken up into pieces
                     |
                     |and writtin to file
                   """.stripMargin)

      val underTest = system.actorOf(Props(new ForwardingParent(
        FilePersistence.props(toPersistInto = testOut, fileOffset = FolderPersistence.FileOffset(0, fileContent.size)),
        testActor)))

      val writes = for {
        (block, offsetIndex) <- Random.shuffle(fileContent.grouped(10).zipWithIndex)
      } yield FilePersistence.Write(block, offsetIndex * 10)

      val doubledUp = writes.toList.flatMap(write => List(write, write))
      val firstHalf = doubledUp take (doubledUp.size / 2) + 1
      val secondHalf = doubledUp drop (doubledUp.size / 2) + 1
      firstHalf foreach (underTest ! _)
      expectNoMsg()
      secondHalf foreach (underTest ! _)
      expectMsgType[FilePersistence.Done]
    }
  }

}

class FolderPersistenceTest extends TestKit(ActorSystem("FolderPersistenceTest"))
with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps with MockitoSugar {

  override def beforeAll(): Unit = {
    val testOut = new File("target") / "testOut"
    FileUtils.forceMkdir(testOut)
  }

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "FolderPersistence" must {
    "input data must be consistent" in {
      val reconstituted =
        FolderPersistenceTest.downloadPieces.take(2).map(_.contiguousStream.get).reduce(_ ++ _)

      val expected = "0\nfile 0 with some text\n/0"
      val chopped = reconstituted.utf8String.substring(0, expected.size)
      chopped mustEqual expected
    }

    "write multiple files within a directory" in {
      val torrent = mock[Torrent]
      val testOut = new File("target") / "testOut" / s"FolderPersistenceTest${Random.nextLong()}"

      Mockito.when(torrent.files).thenReturn(FolderPersistenceTest.files)

      val underTest = watch(system.actorOf(Props(new ForwardingParent(
          FolderPersistence.props(torrent, testOut), testActor)), "multipleFiles"))

      FolderPersistenceTest.downloadPieces foreach { p =>
        underTest ! Persistence.Persist(p)
      }

      val FolderPersistence.Done(dir) = expectMsgType[FolderPersistence.Done]
      dir.listFiles().map(_.getName).toSet mustEqual FolderPersistenceTest.files.map(_.name).toSet

      dir.listFiles() foreach { written =>
        val result = FolderPersistenceTest.results.find {
          case (tf, _) => tf.name == written.getName
        }
        val Some((torrentFile, fileBytes)) = result
        val bytesToString = fileBytes.utf8String
        val fileToString = Source.fromFile(written).getLines().mkString("\n")
        bytesToString mustEqual fileToString
      }

      expectTerminated(underTest)

    }
  }
}

object FolderPersistenceTest {
  val numberOfFiles = 5
  val blockSize = 6
  val blocksPerPiece = 3
  val totalPieceSize = blockSize * blocksPerPiece

  val results: Seq[(TorrentFile, ByteString)] = 0.until(numberOfFiles).map { fileIndex =>
    val fileText = ByteString(
      s"$fileIndex\nfile $fileIndex with some text\n/$fileIndex"
    )
    val torrentFile = TorrentFile(name = s"file$fileIndex", fileText.size)
    (torrentFile, fileText)
  }

  val sequentialBytes: ByteString = results.map(_._2).reduce(_ ++ _)

  val blockBytes: List[ByteString] = sequentialBytes.grouped(blockSize).toList

  val downloadPieces: List[DownloadPiece] = blockBytes.grouped(blocksPerPiece).toList.zipWithIndex.map {
    case (blocks, pieceIndex) =>
      val bytesInPiece = blocks.reduce(_ ++ _)
      DownloadPiece(pieceIndex, bytesInPiece.size, pieceIndex * totalPieceSize, Torrent.hash(bytesInPiece)) ++
        blocks.zipWithIndex.map(b => Block(b._2 * blockSize, b._1))
  }

  def files = results.map(_._1).toList

}
