package io.github.oxlade39.storrent.peer

import org.scalatest.WordSpecLike
import org.scalatest.MustMatchers
import io.github.oxlade39.storrent.core.Torrent
import akka.util.ByteString
import scala.collection.SortedSet

/**
 * @author dan
 */
class DownloadPieceTest extends WordSpecLike with MustMatchers {
  "Block" should {
    "be sortable" in {
      val blockOneBytes: ByteString = ByteString("one")
      val blockTwoBytes: ByteString = ByteString("two")
      val blockOne: Block = Block(0, blockOneBytes)
      val blockTwo: Block = Block(blockOneBytes.size, blockTwoBytes)

      SortedSet(blockOne, blockTwo)(Block.orderByOffset).head mustEqual blockOne
      SortedSet(blockTwo, blockOne)(Block.orderByOffset).head mustEqual blockOne
    }
  }

  "DownloadPiece" should {
    "be a collection of overlapping blocks" in {
      val expectedData = ByteString("Hello World")

      val hello = ByteString("Hello Mars")
      val blockZero = Block(0, hello)
      val world = ByteString(" World")
      val blockOne = Block(data = world, offset = ByteString("Hello").size)

      val piece =
        DownloadPiece(0, expectedData.size, 0, Torrent.hash(expectedData)) + blockZero + blockOne

      val stream = piece.contiguousStream
      stream.get.utf8String mustEqual "Hello World"
    }
  }
}
