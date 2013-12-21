package io.github.oxlade39.storrent.core

import java.io.File
import akka.util.ByteString
import java.net.URI
import scala.io.Source
import java.security.MessageDigest
import java.util.Date


object Torrent {

  val extension = ".torrent"
  val mediaType = "application/x-bittorrent"
  val encoding = "ISO-8859-1"

  def fromFile(file: File): Torrent = {
    val tfr = new TorrentFileReader(file)

    Torrent(
      tfr.name,
      tfr.comment,
      tfr.createdBy,
      tfr.creationDate,
      tfr.files,
      tfr.infoHash,
      tfr.announceList,
      tfr.pieceLength,
      tfr.pieces
    )
  }

  def hash(bytes: ByteString): ByteString = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(bytes.toArray)
    ByteString(md.digest())
  }

}

class TorrentFileReader(file: File) {
  /** The query parameters encoding when parsing byte strings. */
  val BYTE_ENCODING = "ISO-8859-1"

  private[this] val source = Source.fromFile(file, BYTE_ENCODING)

  private[this] val (main) = try {
    val byteArray = source.map(_.toByte).toArray
    BencodeParser.parse(ByteString.fromArray(byteArray)) match {
      case Some(map: BMap) => map
      case other => throw new IllegalArgumentException(s"Unsupported file contents: $other")
    }
  } finally {
    source.close()
  }

  private[this] val info = main("info") match {
    case bmap: BMap => bmap
    case _ => throw new IllegalArgumentException("no info")
  }

  val name: String = info("name").asInstanceOf[BBytes].value.utf8String

  val files: List[TorrentFile] = info.values.get(BBytes("files")) match {
    case Some(items: BList) =>
      items.values.map {
        case file: BMap =>
          val path = file("path") match {
            case p: BList => p.values.map {case b: BBytes => b.value.utf8String }.mkString(File.separator)
          }
          val size = file("length") match {
            case BInt(i) => i
          }
          TorrentFile(path, size)
      }.toList
    case _ =>
      List(TorrentFile(name, info("length").asInstanceOf[BInt].value))
  }

  val announceList = main.get("announce-list") match {
    case Some(l: BList) => {
      val tiers = l.values
        .map(_.asInstanceOf[BList].values.toList
        .map(_.asInstanceOf[BBytes].value.utf8String)
        .map(new URI(_)))
      tiers.toList
    }
    case _ =>
      List(List(new URI(main("announce").asInstanceOf[BBytes].value.utf8String)))
  }


  val comment = main.get("comment").map(_.asInstanceOf[BBytes].stringValue)
  val createdBy = main.get("created by").map(_.asInstanceOf[BBytes].stringValue)
  val creationDate = main.get("creation date").map(_.asInstanceOf[BInt].value.toLong)
  val infoHash = hash(info.encode.toArray)
  val pieceLength = info("piece length").asInstanceOf[BInt].value
  val pieces = info("pieces").asInstanceOf[BBytes].value.grouped(20).toList

  def hash(byteArray: Array[Byte]) = Torrent.hash(ByteString(byteArray))
}

case class Torrent(name: String,
                   comment: Option[String] = None,
                   createdBy: Option[String] = None,
                   creationTimestamp: Option[Long] = None,
                   files: List[TorrentFile],
                   infoHash: ByteString,
                   announceList: List[List[URI]],
                   pieceSize: Int,
                   pieceHashes: List[ByteString],
                   seeder: Boolean = false) {

  def creationDate = creationTimestamp.map(new Date(_))

  def isMultifile = files.size > 1

  lazy val getSize = files.foldLeft(0L)(_ + _.size)

  lazy val hexInfoHash = infoHash.map("%02X" format _).mkString

  lazy val trackerCount = announceList.flatten.toSet.size
}

case class TorrentFile(name: String, size: Long)
