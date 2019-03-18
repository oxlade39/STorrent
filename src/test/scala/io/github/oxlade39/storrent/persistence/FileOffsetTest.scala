package io.github.oxlade39.storrent.persistence

import org.scalatest.WordSpec
import io.github.oxlade39.storrent.peer.{Block, DownloadPiece}
import akka.util.ByteString
import io.github.oxlade39.storrent.core.Torrent
import io.github.oxlade39.storrent.persistence.FolderPersistence.FileOffset
import org.scalatest.MustMatchers


class FileOffsetTest extends WordSpec with MustMatchers {
  import FileOffsetTest._

  "FileOffsetTest" must {
    "chop the start of a DownloadPiece to within bounds" in {
      val fileOffset = FileOffset(0, 3)
      fileOffset.chop(downloadPiece) mustEqual ByteString(0.toByte, 1.toByte, 2.toByte)
    }

    "chop the middle of a DownloadPiece to within bounds" in {
      val fileOffset = FileOffset(10, 20)
      fileOffset.chop(downloadPiece) mustEqual ByteString(10.toByte, 11.toByte)
    }

    "chop a DownloadPiece which is smaller than the end offset" in {
      val fileOffset = FileOffset(3, 6)
      fileOffset.chop(downloadPiece) mustEqual ByteString(3.toByte, 4.toByte, 5.toByte)
    }

    "chop a middle DownloadPiece which overlaps the end of a middle FileOffset" in {
      val fileOffset = FileOffset(28, 56)

      val bytes = ByteString(18.until(36).map(_.toByte).toArray)
      val blocks = bytes.grouped(6).zipWithIndex.map{case (b,i) => Block(i * 6, b)}
      val dp = DownloadPiece(index = 1,
        size = 18,
        offset = 18,
        hash = Torrent.hash(bytes)
      ) ++ blocks.toTraversable

      fileOffset.chop(dp) mustEqual ByteString(28.until(36).map(_.toByte).toArray)
    }

    "chop a middle DownloadPiece which overlaps the end of a begging FileOffset" in {
      val fileOffset = FileOffset(0, 28)

      val bytes = ByteString(18.until(36).map(_.toByte).toArray)
      val blocks = bytes.grouped(6).zipWithIndex.map{case (b,i) => Block(i * 6, b)}
      val dp = DownloadPiece(index = 1,
        size = 18,
        offset = 18,
        hash = Torrent.hash(bytes)
      ) ++ blocks.toTraversable

      fileOffset.chop(dp) mustEqual ByteString(18.until(28).map(_.toByte).toArray)
    }
  }
}

object FileOffsetTest {
  val blocks = Seq(
    Block(0, ByteString(0.toByte, 1.toByte)),
    Block(2, ByteString(2.toByte, 3.toByte)),
    Block(4, ByteString(4.toByte, 5.toByte)),
    Block(6, ByteString(6.toByte, 7.toByte)),
    Block(8, ByteString(8.toByte, 9.toByte)),
    Block(10, ByteString(10.toByte, 11.toByte))
  )
  val blocksAsBytes = blocks.map(_.data).reduce(_ ++ _)
  val downloadPiece = DownloadPiece(0, blocksAsBytes.size, 0, Torrent.hash(blocksAsBytes)) ++ blocks
}