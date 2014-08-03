## STorrent
*STorrent* is a [BitTorrent](http://en.wikipedia.org/wiki/BitTorrent) client written almost entirely in
[Akka](http://akka.io).

*STorrent* heavily leverages Akka's
[reactive IO](http://doc.akka.io/docs/akka/snapshot/scala/io.html) features to provide a (hopefully) simple set of
abstractions around establishing connections with peers, reading/write bytestreams and parsing those bytestreams into
[BitTorrent's message model](https://wiki.theory.org/BitTorrentSpecification#Messages).

[![Build Status](https://travis-ci.org/oxlade39/STorrent.png?branch=master)](https://travis-ci.org/oxlade39/STorrent)

### Inspiration
*STorrent* was heavily inspired by [Ttorrent](https://github.com/mpetazzoni/ttorrent) which I initially attempted to
port to Akka but then decided to start entirely from scratch after taking the [Coursera](https://www.coursera.org/)
Reactive Programming course.

### Testing
*STorrent* has been tested throughout, leveraging [akka-testkit](http://doc.akka.io/docs/akka/snapshot/scala/testing.html)
 and [scalatest](http://www.scalatest.org/) to test the expected inputs/outputs, parent/child communication and actor
 termination, as well as regular specs for non actor classes.

If you contribute please be sure to add plenty of tests.

### Examples
Examples are placed in the `io.github.oxlade39.storrent.example` package

Distilled:

    val sys = ActorSystem("Example")
    // this will create and start an actor heirarchy to download the given torrent using default values
    val download = sys.actorOf(StorrentDownload.props("examples" / "ubuntu-13.10-desktop-amd64.iso.torrent"), "ubuntu")

#### Ubuntu download example
`io.github.oxlade39.storrent.example.Example` demonstrates one example usage of *STorrent* and can be used to successfully
 download [Ubuntu](http://www.ubuntu.com/)

### Unfinished
*STorrent* is not finished! For example serving pieces to connected peers is not implemented at all, in other words
 only downloads are currently supported.

I'm working on fixing that, you can help too.

#### Code review
This is my first attempt at developing anything significant in Akka, following on from the [Coursera](https://www.coursera.org/)
 Reactive Programming course. I'm keen to improve my Akka and Scala so _constructive_ criticism is welcomed.

The remaining work will be tracked in the issues section, the focus on working toward a 1.0 release which will aim to be
 a minimum feature working BitTorrent client.

### Implementation

Notable implementation areas:

#### `io.github.oxlade39.storrent.core`
- `Torrent` The torrent file model
- `BencodeParser` implementation of [Bencode](https://wiki.theory.org/BitTorrentSpecification#Bencoding) parsing
- `BValue` case classes representing the different [Bencoded](https://wiki.theory.org/BitTorrentSpecification#Bencoding) types

#### `io.github.oxlade39.storrent.example`
- `Example` An example of using *STorrent* to download Ubuntu

There is currently no notification of final completion but this shouldn't be hard to add...

#### `io.github.oxlade39.storrent.peer`

#### `io.github.oxlade39.storrent.persistence`
Folder and single File persistence, using `java.nio`

#### `io.github.oxlade39.storrent.piece`
- `Downloader2` Actor currently responsible for managing and co-ordinating child actors which request and download the
 individual pieces and blocks. It currently uses a work pulling pattern to queue the available work (pieces from peers)
 and allow workers to request the next download task (piece from peer) to work on.
 
### Licence

Copyright 2014 Dan Oxlade

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.