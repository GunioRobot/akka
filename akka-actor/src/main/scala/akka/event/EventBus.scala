/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor.ActorRef
import akka.util.Index
import java.util.concurrent.ConcurrentSkipListSet
import java.util.Comparator

/**
 * Represents the base type for EventBuses
 * Internally has an Event type, a Classifier type and a Subscriber type
 *
 * For the Java API, @see akka.event.japi.*
 */
trait EventBus {
  type Event
  type Classifier
  type Subscriber

  /**
   * Attempts to register the subscriber to the specified Classifier
   * @returns true if successful and false if not (because it was already subscribed to that Classifier, or otherwise)
   */
  def subscribe(subscriber: Subscriber, to: Classifier): Boolean

  /**
   * Attempts to deregister the subscriber from the specified Classifier
   * @returns true if successful and false if not (because it wasn't subscribed to that Classifier, or otherwise)
   */
  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean

  /**
   * Attempts to deregister the subscriber from all Classifiers it may be subscribed to
   */
  def unsubscribe(subscriber: Subscriber): Unit

  /**
   * Publishes the specified Event to this bus
   */
  def publish(event: Event): Unit
}

/**
 * Represents an EventBus where the Subscriber type is ActorRef
 */
trait ActorEventBus extends EventBus {
  type Subscriber = ActorRef
}

/**
 * Can be mixed into an EventBus to specify that the Classifier type is ActorRef
 */
trait ActorClassifier { self: EventBus ⇒
  type Classifier = ActorRef
}

/**
 * Can be mixed into an EventBus to specify that the Classifier type is a Function from Event to Boolean (predicate)
 */
trait PredicateClassifier { self: EventBus ⇒
  type Classifier = Event ⇒ Boolean
}

/**
 * Maps Subscribers to Classifiers using equality on Classifier to store a Set of Subscribers (hence the need for compareSubscribers)
 * Maps Events to Classifiers through the classify-method (so it knows who to publish to)
 *
 * The compareSubscribers need to provide a total ordering of the Subscribers
 */
trait LookupClassification { self: EventBus ⇒

  protected final val subscribers = new Index[Classifier, Subscriber](mapSize(), new Comparator[Subscriber] {
    def compare(a: Subscriber, b: Subscriber): Int = compareSubscribers(a, b)
  })

  /**
   * This is a size hint for the number of Classifiers you expect to have (use powers of 2)
   */
  protected def mapSize(): Int

  /**
   * Provides a total ordering of Subscribers (think java.util.Comparator.compare)
   */
  protected def compareSubscribers(a: Subscriber, b: Subscriber): Int

  /**
   * Returns the Classifier associated with the given Event
   */
  protected def classify(event: Event): Classifier

  /**
   * Publishes the given Event to the given Subscriber
   */
  protected def publish(event: Event, subscriber: Subscriber): Unit

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean = subscribers.put(to, subscriber)

  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = subscribers.remove(from, subscriber)

  def unsubscribe(subscriber: Subscriber): Unit = subscribers.removeValue(subscriber)

  def publish(event: Event): Unit = {
    val i = subscribers.valueIterator(classify(event))
    while (i.hasNext) publish(event, i.next())
  }
}

/**
 * Maps Classifiers to Subscribers and selects which Subscriber should receive which publication through scanning through all Subscribers
 * through the matches(classifier, event) method
 *
 * Note: the compareClassifiers and compareSubscribers must together form an absolute ordering (think java.util.Comparator.compare)
 */
trait ScanningClassification { self: EventBus ⇒
  protected final val subscribers = new ConcurrentSkipListSet[(Classifier, Subscriber)](new Comparator[(Classifier, Subscriber)] {
    def compare(a: (Classifier, Subscriber), b: (Classifier, Subscriber)): Int = {
      val cM = compareClassifiers(a._1, b._1)
      if (cM != 0) cM
      else compareSubscribers(a._2, b._2)
    }
  })

  /**
   * Provides a total ordering of Classifiers (think java.util.Comparator.compare)
   */
  protected def compareClassifiers(a: Classifier, b: Classifier): Int

  /**
   * Provides a total ordering of Subscribers (think java.util.Comparator.compare)
   */
  protected def compareSubscribers(a: Subscriber, b: Subscriber): Int

  /**
   * Returns whether the specified Classifier matches the specified Event
   */
  protected def matches(classifier: Classifier, event: Event): Boolean

  /**
   * Publishes the specified Event to the specified Subscriber
   */
  protected def publish(event: Event, subscriber: Subscriber): Unit

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean = subscribers.add((to, subscriber))

  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = subscribers.remove((from, subscriber))

  def unsubscribe(subscriber: Subscriber): Unit = {
    val i = subscribers.iterator()
    while (i.hasNext) {
      val e = i.next()
      if (compareSubscribers(subscriber, e._2) == 0) i.remove()
    }
  }

  def publish(event: Event): Unit = {
    val currentSubscribers = subscribers.iterator()
    while (currentSubscribers.hasNext) {
      val (classifier, subscriber) = currentSubscribers.next()
      if (matches(classifier, event))
        publish(event, subscriber)
    }
  }
}

/**
 * Maps ActorRefs to ActorRefs to form an EventBus where ActorRefs can listen to other ActorRefs
 */
trait ActorClassification { self: ActorEventBus with ActorClassifier ⇒
  import java.util.concurrent.ConcurrentHashMap
  import scala.annotation.tailrec

  protected val mappings = new ConcurrentHashMap[ActorRef, Vector[ActorRef]](mapSize)

  @tailrec
  protected final def associate(monitored: ActorRef, monitor: ActorRef): Boolean = {
    val current = mappings get monitored
    current match {
      case null ⇒
        if (monitored.isShutdown) false
        else {
          if (mappings.putIfAbsent(monitored, Vector(monitor)) ne null) associate(monitored, monitor)
          else if (monitored.isShutdown) !dissociate(monitored, monitor) else true
        }
      case raw: Vector[_] ⇒
        val v = raw.asInstanceOf[Vector[ActorRef]]
        if (monitored.isShutdown) false
        if (v.contains(monitor)) true
        else {
          val added = v :+ monitor
          if (!mappings.replace(monitored, v, added)) associate(monitored, monitor)
          else if (monitored.isShutdown) !dissociate(monitored, monitor) else true
        }
    }
  }

  protected final def dissociate(monitored: ActorRef): Iterable[ActorRef] = {
    @tailrec
    def dissociateAsMonitored(monitored: ActorRef): Iterable[ActorRef] = {
      val current = mappings get monitored
      current match {
        case null ⇒ Vector.empty[ActorRef]
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          if (!mappings.remove(monitored, v)) dissociateAsMonitored(monitored)
          else v
      }
    }

    def dissociateAsMonitor(monitor: ActorRef): Unit = {
      val i = mappings.entrySet.iterator
      while (i.hasNext()) {
        val entry = i.next()
        val v = entry.getValue
        v match {
          case raw: Vector[_] ⇒
            val monitors = raw.asInstanceOf[Vector[ActorRef]]
            if (monitors.contains(monitor))
              dissociate(entry.getKey, monitor)
          case _ ⇒ //Dun care
        }
      }
    }

    try { dissociateAsMonitored(monitored) } finally { dissociateAsMonitor(monitored) }
  }

  @tailrec
  protected final def dissociate(monitored: ActorRef, monitor: ActorRef): Boolean = {
    val current = mappings get monitored
    current match {
      case null ⇒ false
      case raw: Vector[_] ⇒
        val v = raw.asInstanceOf[Vector[ActorRef]]
        val removed = v.filterNot(monitor ==)
        if (removed eq raw) false
        else if (removed.isEmpty) {
          if (!mappings.remove(monitored, v)) dissociate(monitored, monitor) else true
        } else {
          if (!mappings.replace(monitored, v, removed)) dissociate(monitored, monitor) else true
        }
    }
  }

  /**
   * Returns the Classifier associated with the specified Event
   */
  protected def classify(event: Event): Classifier

  /**
   * This is a size hint for the number of Classifiers you expect to have (use powers of 2)
   */
  protected def mapSize: Int

  def publish(event: Event): Unit = mappings.get(classify(event)) match {
    case null           ⇒
    case raw: Vector[_] ⇒ raw.asInstanceOf[Vector[ActorRef]] foreach { _ ! event }
  }

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean = associate(to, subscriber)
  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = dissociate(from, subscriber)
  def unsubscribe(subscriber: Subscriber): Unit = dissociate(subscriber)
}