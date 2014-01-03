package io.github.oxlade39.storrent.announce

import io.github.oxlade39.storrent.core.Torrent
import akka.actor._
import scala.util.Random
import java.net.URI
import akka.actor.Terminated
import scala.Some
import io.github.oxlade39.storrent.peer.Discovered
import akka.event.LoggingReceive

/**
 * @author dan
 */
object Tracker {
  def props(torrent: Torrent) = Props(new Tracker(torrent))
}

class Tracker(torrent: Torrent) extends Actor with ActorLogging {
  import io.github.oxlade39.storrent.Storrent.LocalPeer
  import context._

  val trackerTiers = torrent.announceList.map(tier => Random.shuffle(tier))

  var tier = 0
  var client = 0
  def current = trackerTiers(tier)(client)

  def clientFor(uri: URI): Props = uri.getScheme match {
    case "http" => Props(new HttpTrackerClient(uri.toURL, request))
    case "https" => ??? //TODO
    case "udp" => ??? //TODO
  }

  def request: TrackerRequest = TrackerRequest(
    infoHash = torrent.infoHash,
    peerId = LocalPeer.id,
    port = LocalPeer.port,
    uploaded = 0, downloaded = 0, left = torrent.getSize,
    acceptCompact = false, noPeerId = false, event = Some(Started)
  )

  def receive = awaitingResponse(watch(actorOf(clientFor(current), "tracker-client")), current)

  def awaitingResponse(client: ActorRef, uri: URI): Receive = LoggingReceive {
    case r: NormalTrackerResponse =>
      unwatch(client)
      parent ! Discovered(r.peers)

    case f: FailureTrackerResponse =>
      unwatch(client)
      log.info("tracker on {} failed with {}", uri, f)
      become(awaitingResponse(watch(actorOf(clientFor(nextTracker))), current))

    case Terminated(trackerClient) if trackerClient == client =>
      unwatch(client)
      log.warning("tracker {} at {} died", client, uri)
      become(awaitingResponse(watch(actorOf(clientFor(nextTracker))), current))
  }

  def nextTracker: URI = {
    client = (client + 1) % trackerTiers(tier).size
    if (client == 0)
      tier = (tier + 1) % trackerTiers.size
    current
  }
}
