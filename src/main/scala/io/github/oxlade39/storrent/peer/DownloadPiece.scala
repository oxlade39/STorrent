package io.github.oxlade39.storrent.peer

import akka.util.ByteString
import io.github.oxlade39.storrent.core.Torrent
import java.nio.ByteBuffer
import scala.collection.{SortedSet, mutable}

object DownloadPiece {
  val ordering = Ordering[Int].on[DownloadPiece](_.index)
}

object Block {
  val orderByOffset = Ordering[Int].on[Block](block ⇒ block.offset)
}

case class Block(offset: Int, data: ByteString)

case class DownloadPiece(
  index: Int,
  size: Int,
  hash: ByteString,
  data: SortedSet[Block] = mutable.SortedSet()(Block.orderByOffset)
) {

  def offset = index * size

  def totalBlockSize: Int = data.foldLeft(0)(_ + _.data.size)
  def hasEnoughBytesToBeComplete = totalBlockSize >= size

  lazy val contiguousStream: Option[ByteString] = {
    val initial: (Option[ByteBuffer], Int) = (Some(ByteBuffer.allocate(size)), 0)
    val result = data.foldLeft(initial){ (accum, block) ⇒
      val (buffer, contiguousBytes) = accum
      if (buffer.isEmpty || contiguousBytes < block.offset)
        (None, 0)
      else {
        val appendedBuffer = buffer.map{ b =>
          b.position(block.offset)
          b.put(block.data.toArray)
          b
        }
        if (appendedBuffer.isDefined)
          (Some(appendedBuffer.get), contiguousBytes + appendedBuffer.get.position())
        else
          (None, 0)
      }
    }
    result._1.map{ buf ⇒
      buf.rewind()
      ByteString(buf)
    }
  }

  lazy val actualHash = contiguousStream.map(s ⇒ Torrent.hash(s))

  lazy val isValid: Boolean = actualHash.exists(_.equals(hash))

  def +(block: Block): DownloadPiece = copy(data = data + block)
  def ++(blocks: Set[Block]) = copy(data = data ++ blocks)
}