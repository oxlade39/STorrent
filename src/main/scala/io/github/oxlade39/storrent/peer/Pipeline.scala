package io.github.oxlade39.storrent.peer

import akka.io.{SymmetricPipePair, PipelineContext, SymmetricPipelineStage}
import akka.util.ByteString
import java.nio.ByteOrder
import scala.annotation.tailrec

/**
 * @author dan
 */
object Pipeline {
  /**
   * All of the remaining messages in the protocol take the form of
   * <length prefix><message ID><payload>.
   * The length prefix is a four byte big-endian value.
   * The message ID is a single decimal byte.
   * The payload is message dependent
   */
  implicit val byteOrder = ByteOrder.BIG_ENDIAN
  val lengthBytes = 4
  val idBytes = 1
  val headerSize = lengthBytes + idBytes
  // TODO ???
  val maxSize = Long.MaxValue
}

case class PartialMessage(length: Int, messageId: Option[Int], body: ByteString)

class MessageTypeStage extends SymmetricPipelineStage[PipelineContext, PartialMessage, ByteString] {
  import Pipeline._

  override def apply(ctx: PipelineContext) =
    new SymmetricPipePair[PartialMessage, ByteString] {
      var buffer = None: Option[ByteString]

      def commandPipeline: PartialMessage => Iterable[Result] =
      { pm: PartialMessage =>
        val bb = ByteString.newBuilder.putLongPart(pm.length, lengthBytes)
        pm.messageId.foreach(mId => bb.putLongPart(mId, idBytes))
        bb ++= pm.body
        ctx.singleCommand(bb.result())
      }

      /*
       * This is how events (reads) are transformed: append the received
       * ByteString to the buffer (if any) and extract the frames from the
       * result. In the end store the new buffer contents and return the
       * list of events (i.e. `Left(...)`).
       */
      override def eventPipeline: ByteString ⇒ Iterable[Result] =
      { bs: ByteString ⇒
        val data = if (buffer.isEmpty) bs else buffer.get ++ bs
        val (nb, frames) = extractPartials(data, Nil)
        buffer = nb
        /*
         * please note the specialized (optimized) facility for emitting
         * just a single event
         */
        frames match {
          case Nil        ⇒ Nil
          case one :: Nil ⇒ ctx.singleEvent(one)
          case many       ⇒ many reverseMap (Left(_))
        }
      }
    }

  /**
   * Extract as many complete frames as possible from the given ByteString
   * and return the remainder together with the extracted frames in reverse
   * order.
   */
  @tailrec
  private def extractPartials(bs: ByteString, acc: List[PartialMessage]): (Option[ByteString], Seq[PartialMessage]) = {
    if (bs.isEmpty) {
      (None, acc)
    } else if (bs.length < headerSize) {
      // special handling for KeepAlive zero length Message (there's always a special case!)
      if (bs.length == lengthBytes && bs.iterator.getLongPart(lengthBytes).toInt == 0) {
        extractPartials(bs drop lengthBytes,
          PartialMessage(length = 0,
            messageId = None,
            body = ByteString.empty) :: acc)
      } else {
        (Some(bs.compact), acc)
      }
    } else {
      val length = bs.iterator.getLongPart(lengthBytes).toInt
      if (length < 0 || length > maxSize)
        throw new IllegalArgumentException(
          s"received too large frame of size $length (max = $maxSize)")

      val total = lengthBytes + length

      if (bs.length >= total) {
        val itr = bs.iterator
        val body: ByteString = bs.slice(headerSize, total)
        extractPartials(bs drop total,
          PartialMessage(length = itr.getLongPart(lengthBytes).toInt,
                         messageId = Some(itr.getLongPart(idBytes).toInt),
                         body = body) :: acc)
      } else {
        (Some(bs.compact), acc)
      }
    }
  }
}

class MessageStage extends SymmetricPipelineStage[PipelineContext, Message, PartialMessage] {
  import Pipeline._

  override def apply(ctx: PipelineContext) =
    new SymmetricPipePair[Message, PartialMessage] {

      def commandPipeline: Message => Iterable[Result] =
      { m: Message =>
        ctx.singleCommand(PartialMessage(m.length, m.messageId, m.payload.getOrElse(ByteString.empty)))
      }

      def eventPipeline: PartialMessage => Iterable[Result] =
      { pm: PartialMessage =>
        if (pm.messageId.isEmpty)
          ctx.singleEvent(KeepAlive)
        else {
          val msgId = pm.messageId.get
          val body = pm.body
          val key: (Int, ByteString) = (msgId, body)
          val singleEvent = parseMessage.andThen(msg => ctx.singleEvent(msg))
          singleEvent.applyOrElse[(Int, ByteString), Iterable[Result]](key, _ => Iterable.empty[Result])
        }
      }

      def parseMessage: PartialFunction[(Int, ByteString), Message] = {
        case (0, _) => Choke
        case (1, _) => UnChoke
        case (2, _) => Interested
        case (3, _) => NotInterested

        case (4, body) => Have(body.iterator.getLongPart(4).toInt)

        case (5, body) =>
          val xs: Array[Byte] = new Array[Byte](body.length)
          body.iterator.getBytes(xs)
          val withoutSignExtention: Array[Int] = xs.map(_.toInt & 0xff)
          val setBits: Seq[Boolean] = BitOps.asBooleans(withoutSignExtention)
          Bitfield(setBits)

        case (6, body) =>
          val itr = body.iterator
          Request(
            index = itr.getLongPart(4).toInt,
            begin = itr.getLongPart(4).toInt,
            requestLength = itr.getLongPart(4).toInt
          )

        case (7, body) =>
          val itr = body.iterator
          Piece(
            itr.getLongPart(4).toInt,
            itr.getLongPart(4).toInt,
            itr.toByteString
          )

        case (8, body) =>
          val itr = body.iterator
          Cancel(
            index = itr.getLongPart(4).toInt,
            begin = itr.getLongPart(4).toInt,
            requestLength = itr.getLongPart(4).toInt
          )

        case (9, body) => Port(body.iterator.getLongPart(2).toShort)
      }
    }
}