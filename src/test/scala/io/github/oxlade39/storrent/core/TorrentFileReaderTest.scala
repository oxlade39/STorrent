package io.github.oxlade39.storrent.core

import org.scalatest.WordSpecLike
import java.io.File
import org.scalatest.matchers.MustMatchers
import java.net.URI

/**
 * @author dan
 */
class TorrentFileReaderTest extends WordSpecLike with MustMatchers {
  "TorrentFileReader" must {
    "read real torrent file" in {

      val reader: TorrentFileReader = new TorrentFileReader(file("examples/ubuntu.torrent"))
      reader.pieceLength mustEqual 524288

      reader.announceList mustEqual List(
        List(new URI("http://torrent.ubuntu.com:6969/announce")),
        List(new URI("http://ipv6.torrent.ubuntu.com:6969/announce"))
      )

      reader.name mustEqual "ubuntu-13.04-desktop-amd64.iso"
    }
  }

  def file(location: String) =
    new File(Thread.currentThread().getContextClassLoader.getResource(location).toURI)
}
