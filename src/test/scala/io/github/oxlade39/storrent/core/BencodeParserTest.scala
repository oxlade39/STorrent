package io.github.oxlade39.storrent.core

import org.scalatest.WordSpecLike
import org.scalatest.matchers.MustMatchers
import akka.util.ByteString

/**
 * @author dan
 */
class BencodeParserTest extends WordSpecLike with MustMatchers {
  "BencodeParser" must {
    "parse simple integers" in {
      val input = ByteString("i29e")
      BencodeParser.parse(input) mustEqual Some(BInt(29))
    }

    "parse simple bytes" in {
      val input = ByteString("4:spam")
      BencodeParser.parse(input) mustEqual Some(BBytes("spam"))
    }

    "parse simple lists" in {
      val input = ByteString("li29ei30ei40ee")
      BencodeParser.parse(input) mustEqual Some(BList(BInt(29), BInt(30), BInt(40)))
    }

    "parse simple dictionaries" in {
      val input = ByteString("d3:bar4:spam3:fooi42ee")
      BencodeParser.parse(input) mustEqual
        Some(BMap(Map(BBytes("foo") -> BInt(42), BBytes("bar") -> BBytes("spam"))))
    }

    "parse dictionaries with lists" in {
      val input = ByteString("d3:barli42ee3:fooi43e")
      BencodeParser.parse(input) mustEqual
        Some(BMap(Map(
          BBytes("bar") -> BList(BInt(42)),
          BBytes("foo") -> BInt(43)
        )))
    }

    "parse dictionaries with dictionary values" in {
      val input = ByteString("d3:bard3:fooi42eee")
      BencodeParser.parse(input) mustEqual
        Some(BMap(Map(
          BBytes("bar") -> BMap(Map(
            BBytes("foo") -> BInt(42)))
        )))
    }

    "parse multi type lists" in {
      val input = ByteString("l4:spami42ee")
      BencodeParser.parse(input) mustEqual Some(BList(BBytes("spam"), BInt(42)))
    }

    "parse lists of list" in {
      val input = ByteString("lli42eee")
      BencodeParser.parse(input) mustEqual Some(BList(BList(BInt(42))))
    }
  }

  "BIntByteConsumer" must {
    "not consume all bytes" in {
      val input = ByteString("i29ei30ei40e")
      val iterator = input.iterator
      val output = BIntByteConsumer.nextBValue(iterator)
      output mustEqual Some(BInt(29))
      iterator.toByteString.utf8String mustEqual "i30ei40e"
    }
  }
}
