package io.github.oxlade39.storrent.core

import akka.util.{ByteIterator, ByteString}
import org.slf4j.LoggerFactory

object BencodeParser {
  import BencodeIdentifiers._
  import BValueByteConsumers._

  val logger = LoggerFactory.getLogger(BencodeParser.getClass)

  def parse[T >: BValue](bytes: ByteString): Option[BValue] =
    if (bytes.isEmpty) None
    else {
      bytes.head match {
        case b if b == 'i' =>
          BIntByteConsumer.nextBValue(bytes.iterator)

        case b if b == 'l' =>
          val inner = bytes.slice(1, bytes.size - 1)
          Some(BList(parseList(inner):_*))

        case b if b == 'd' =>
          val inner = bytes.slice(1, bytes.size - 1)
          Some(BMap(parseMap(inner)))

        case b if digits.contains(b) =>
          BBytesBytesConsumer.nextBValue(bytes.iterator)

        case _ => None
      }
  }

  def parseList(remaining: ByteString): Seq[BValue] = {
    var accum = Seq.empty[BValue]
    val iterator = remaining.iterator
    do {
      val key = iterator.head
      val parsed = typeSpecificParsers(key).nextBValue(iterator)
      parsed.foreach{ p =>
        accum = accum :+ p
      }
    } while (!iterator.isEmpty)
    accum
  }

  def parseMap(remaining: ByteString): Map[BBytes, BValue] = {
    var accum = Map.empty[BBytes, BValue]
    val iterator = remaining.iterator
    do {
      val key = BBytesBytesConsumer.nextBValue(iterator)
      val subParser = typeSpecificParsers.get(iterator.head)
      if (subParser.isEmpty) {
        logger.error("no parser for key {}", iterator.getByte.toChar)
      }
      val value = subParser.flatMap(_.nextBValue(iterator))
      for {
        k <- key
        v <- value
      } yield {
        accum += (k -> v)
      }
    } while (!iterator.isEmpty)
    accum
  }

}

object BencodeIdentifiers {
  val digits = List('0', '1', '2', '3', '4', '5', '6', '7', '8', '9').map(_.toByte)

  val identifiers = ('i'.toByte :: 'l'.toByte :: 'd'.toByte :: digits).toSet
}

object BValueByteConsumers {
  val typeSpecificParsers = Map[Byte, BValueByteConsumer[_ <: BValue]](
    'i'.toByte -> BIntByteConsumer,
    '0'.toByte -> BBytesBytesConsumer,
    '1'.toByte -> BBytesBytesConsumer,
    '2'.toByte -> BBytesBytesConsumer,
    '3'.toByte -> BBytesBytesConsumer,
    '4'.toByte -> BBytesBytesConsumer,
    '5'.toByte -> BBytesBytesConsumer,
    '6'.toByte -> BBytesBytesConsumer,
    '7'.toByte -> BBytesBytesConsumer,
    '8'.toByte -> BBytesBytesConsumer,
    '9'.toByte -> BBytesBytesConsumer,
    'l'.toByte -> BListBytesConsumer,
    'd'.toByte -> BMapBytesConsumer
  )
}

sealed trait BValueByteConsumer[T <: BValue] {
  def nextBValue(b: ByteIterator): Option[T] = {
    val head = b.head
    if (indicator(head)) _nextBValue(b.drop(1))
    else None
  }
  def indicator: Byte => Boolean
  protected def _nextBValue(b: ByteIterator): Option[T]
}

case object BIntByteConsumer extends BValueByteConsumer[BInt] {
  import BencodeIdentifiers._

  val terminators = (identifiers -- digits) + 'e'.toByte

  def indicator = _ == 'i'

  def _nextBValue(b: ByteIterator): Option[BInt] = {
    val (self, copy) = b.duplicate

    val bodyWithTerminator = copy.takeWhile(i => !terminators.contains(i))
    if (bodyWithTerminator.isEmpty) None
    else {
      val toByteString = bodyWithTerminator.toByteString
      val body = toByteString.slice(0, toByteString.size)
      val integerVal = body.utf8String.toInt
      self.drop(toByteString.size + 1)
      Some(BInt(integerVal))
    }
  }
}

case object BBytesBytesConsumer extends BValueByteConsumer[BBytes] {
  val indicators = Set('0', '1', '2', '3', '4', '5', '6', '7', '8', '9').map(_.toByte)
  def indicator = indicators.contains

  override def nextBValue(b: ByteIterator): Option[BBytes] = {
    val (self, copy) = b.duplicate
    val sizeBytes = copy.takeWhile(byte => indicators.contains(byte) && byte != ':'.toByte)
    val rawSizeBytes = new Array[Byte](sizeBytes.size)

    if (rawSizeBytes.length == 0) {
      None
    } else {
      self.getBytes(rawSizeBytes)
      val actualSize = ByteString(rawSizeBytes).utf8String.toInt
      // drop ':'
      self.drop(1)
      val actualBytes = new Array[Byte](actualSize)
      self.getBytes(actualBytes)
      Some(BBytes(ByteString(actualBytes)))
    }
  }

  protected def _nextBValue(b: ByteIterator) = ???
}

case object BListBytesConsumer extends BValueByteConsumer[BList] {
  import BValueByteConsumers._
  val logger = LoggerFactory.getLogger(BListBytesConsumer.getClass)

  def indicator = _ == 'l'

  protected def _nextBValue(iterator: ByteIterator) = {
    var accum = Seq.empty[BValue]
//    val (self, iterator) = b.duplicate
    var parsed = Option.empty[BValue]
    do {
      val key = iterator.head
      val subParser = typeSpecificParsers.get(key)
      if (subParser.isEmpty) {
        val skip = iterator.getByte
        logger.debug("skipping terminator '{}'", skip.toChar)
      }
      parsed = subParser.flatMap(_.nextBValue(iterator))
      parsed.foreach{ p =>
        accum = accum :+ p
      }
    } while (!iterator.isEmpty && parsed.isDefined)
    Some(BList(accum:_*))
  }
}

case object BMapBytesConsumer extends BValueByteConsumer[BMap] {
  import BValueByteConsumers._

  val logger = LoggerFactory.getLogger(BMapBytesConsumer.getClass)

  def indicator = _ == 'd'

  protected def _nextBValue(iterator: ByteIterator) = {
    var accum = Map[BBytes, BValue]()
    do {
      val key = BBytesBytesConsumer.nextBValue(iterator)
      val subParser = typeSpecificParsers.get(iterator.head)
      if (subParser.isEmpty) {
        logger.error("no parser for key {}", iterator.getByte.toChar)
      }
      val value = subParser.flatMap(_.nextBValue(iterator))
      for {
        k <- key
        v <- value
      } yield {
        accum += (k -> v)
      }
    } while (!iterator.isEmpty)
    Some(BMap(accum))
  }
}