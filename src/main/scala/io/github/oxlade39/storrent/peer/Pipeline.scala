package io.github.oxlade39.storrent.peer

import java.nio.ByteOrder

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Framing, Source, SourceQueueWithComplete}
import akka.util.ByteString
import io.github.oxlade39.storrent.peer.PartialMessageBytesParser.Plus4
import io.github.oxlade39.storrent.peer.Pipeline.maxSize

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
  implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
  val lengthBytes = 4
  val idBytes = 1
  val headerSize: Int = lengthBytes + idBytes

  val maxSize = 50000 // TODO ???
}

case class PartialMessage(length: Int, messageId: Option[Int], body: ByteString) {

  import Pipeline._

  def toByteString: ByteString = {
    val bb = ByteString.newBuilder.putLongPart(length, lengthBytes)
    messageId.foreach(mId => bb.putLongPart(mId, idBytes))
    bb ++= body
    bb.result()
  }
}

object PartialMessageBytesParser {

  val toPartialMessage: ByteString => PartialMessage = b => {
    import Pipeline.byteOrder
    val itr = b.iterator
    val length = itr.getLongPart(Pipeline.lengthBytes).toInt
    length match {
      case 0 => PartialMessage(length, None, ByteString.empty)
      case nonZero =>
        val msgId = itr.getLongPart(Pipeline.idBytes).toInt
        val body = itr.toByteString
        PartialMessage(nonZero, Some(msgId), body)
    }

  }

  private val Plus4: (Array[Byte], Int) ⇒ Int = (arr, i) => {
    i + 4
  }

  val parserQueue: Source[PartialMessage, SourceQueueWithComplete[ByteString]] = Source.queue[ByteString](100, OverflowStrategy.backpressure)
    .via(Framing.lengthField(Pipeline.lengthBytes, 0, maxSize, Pipeline.byteOrder, Plus4))
    .map(PartialMessageBytesParser.toPartialMessage)
}

object MessageParser {

  import Pipeline.byteOrder

  val parser: PartialMessage => Message = {
    case PartialMessage(length, None, body) =>
      KeepAlive
    case PartialMessage(length, Some(id), body) =>
      parseMessage.apply(id, body)
    case unknown => throw new UnsupportedOperationException("could parse " + unknown)
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

    case (20, body) => Extended(body)
  }
}

object ReactiveMessageParsing {

  /**
    * not sure why???
    */
  private val Plus4: (Array[Byte], Int) ⇒ Int = (arr, i) => {
    i + 4
  }

  val byteStringFlow: Flow[ByteString, Message, NotUsed] = Flow[ByteString]
    .via(Framing.lengthField(Pipeline.lengthBytes, 0, maxSize, Pipeline.byteOrder, Plus4))
    .map(PartialMessageBytesParser.toPartialMessage)
    .map(MessageParser.parser)

  val byteStringParserQueue: Source[Message, SourceQueueWithComplete[ByteString]] =
    Source.queue[ByteString](100, OverflowStrategy.backpressure)
    .via(byteStringFlow)

}