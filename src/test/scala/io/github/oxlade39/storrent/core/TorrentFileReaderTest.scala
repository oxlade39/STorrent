package io.github.oxlade39.storrent.core

import org.scalatest.WordSpecLike
import java.io.File
import org.scalatest.MustMatchers
import java.net.URI
import io.github.oxlade39.storrent.test.util.FileOps
import akka.util.ByteString

/**
 * @author dan
 */
class TorrentFileReaderTest extends WordSpecLike with MustMatchers with FileOps {
  "TorrentFileReader" must {
    "read real torrent file" in {

      val reader: TorrentFileReader = new TorrentFileReader(file("examples/ubuntu.torrent"))
      reader.pieceLength mustEqual 524288

      reader.announceList mustEqual List(
        List(new URI("http://torrent.ubuntu.com:6969/announce")),
        List(new URI("http://ipv6.torrent.ubuntu.com:6969/announce"))
      )

      reader.name mustEqual "ubuntu-10.04.4-server-amd64.iso"
    }

    "correctly read the infoHash" in {
      val expected = ByteString(8, -116, 47, 77, 95, 62, 80, 26, 56, 125, -127, -128, 22, -72, -71, -58, 47, -51, -80, 33)
      val reader = new TorrentFileReader("examples" / "ubuntu.torrent")
      reader.infoHash mustEqual expected
    }
  }
}
