package be.yannickdeturck.lagomshopscala.order.impl

import java.util.UUID

import akka.NotUsed
import akka.stream.scaladsl.Sink
import be.yannickdeturck.lagomshopscala.item.api.{GetItemsResponse, Item, ItemEvent, ItemService}
import be.yannickdeturck.lagomshopscala.order
import be.yannickdeturck.lagomshopscala.order.api
import be.yannickdeturck.lagomshopscala.order.api.OrderService
import com.datastax.driver.core.utils.UUIDs
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.transport.{BadRequest, NotFound}
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LocalServiceLocator}
import com.lightbend.lagom.scaladsl.testkit.{ServiceTest, TestTopicComponents}
import org.scalatest.{AsyncWordSpec, BeforeAndAfterAll, Matchers}
import play.api.libs.ws.ahc.AhcWSComponents

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

/**
  * @author Yannick De Turck
  */
class OrderServiceSpec extends AsyncWordSpec with Matchers with BeforeAndAfterAll {

  val validItemId: UUID = UUIDs.timeBased()
  val otherValidItemId: UUID = UUIDs.timeBased()

  private val server = ServiceTest.startServer(
    ServiceTest.defaultSetup.withCassandra(true)) { ctx =>
    new LagomApplication(ctx) with OrderComponents with LocalServiceLocator with AhcWSComponents with TestTopicComponents {
      lazy val itemService = new ItemService {
        override def createItem: ServiceCall[Item, Item] = ???

        override def getItem(id: UUID): ServiceCall[NotUsed, Item] = ServiceCall { _ =>
          if (id == validItemId || id == otherValidItemId) {
            Future(Item(Some(id), "title", "description", BigDecimal.valueOf(25.00)))
          } else {
            Future.failed(NotFound(s"Item $id not found"))
          }
        }

        override def getItems: ServiceCall[NotUsed, GetItemsResponse] = ???

        override def itemEvents: Topic[ItemEvent] = ???
      }
    }
  }

  val orderService: OrderService = server.serviceClient.implement[OrderService]

  override protected def afterAll(): Unit = server.stop()

  "order service" should {
    "allow creating an order with a valid item" in {
      for {
        createdOrder <- orderService.createOrder.invoke(api.Order(None, validItemId, 3, "Yannick"))
      } yield {
        createdOrder.safeId should not be null
        createdOrder.itemId should not be null
        createdOrder.amount should be(3)
        createdOrder.customer should be("Yannick")
      }
    }

    "fail when creating an order with an invalid item" in { // TODO write this test correctly
      orderService.createOrder.invoke(api.Order(None, UUID.randomUUID(), 3, "Yannick")).map { order =>
        fail("no order should've been created")
      }.recoverWith {
        case (err: BadRequest) =>
          err.exceptionMessage.detail should be ("Invalid item specified")
      }
    }

    "allow looking up a created order" in {
      val inputOrder = api.Order(None, validItemId, 3, "Yannick")
      for {
        createdOrder <- orderService.createOrder.invoke(inputOrder)
        lookupOrder <- orderService.getOrder(createdOrder.safeId).invoke
      } yield {
        createdOrder should ===(lookupOrder)
      }
    }

    "allow looking up all created orders" in {
      val inputOrder1 = order.api.Order(None, validItemId, 3, "Yannick")
      val inputOrder2 = order.api.Order(None, validItemId, 8, "John")
      val inputOrder3 = order.api.Order(None, validItemId, 12, "Lauren")
      (for {
        createdOrder1 <- orderService.createOrder.invoke(inputOrder1)
        createdOrder2 <- orderService.createOrder.invoke(inputOrder2)
        createdOrder3 <- orderService.createOrder.invoke(inputOrder3)
      } yield {
        awaitSuccess() {
          for {
            lookupOrdersResponse <- orderService.getOrders.invoke
          } yield {
            lookupOrdersResponse.orders should contain allOf(createdOrder1, createdOrder2, createdOrder3)
          }
        }
      }).flatMap(identity)
    }

    "publish order events on the topic" in {
      implicit val system = server.actorSystem
      implicit val mat = server.materializer

      for {
        createdOrder <- orderService.createOrder.invoke(api.Order(None, otherValidItemId, 5, "John"))
        events <- orderService.orderEvents.subscribe.atMostOnceSource
          .filter(_.id == createdOrder.safeId)
          .take(1)
          .runWith(Sink.seq)
      } yield {
        events.size shouldBe 1
        events.head shouldBe an[api.OrderCreated]
        val event = events.head.asInstanceOf[api.OrderCreated]
        event.itemId should be(otherValidItemId)
        event.amount should be(5)
        event.customer should be("John")
      }
    }
  }

  // TODO move to test util or something like that in order to reuse it
  def awaitSuccess[T](maxDuration: FiniteDuration = 10.seconds, checkEvery: FiniteDuration = 100.milliseconds)(block: => Future[T]): Future[T] = {
    val checkUntil = System.currentTimeMillis() + maxDuration.toMillis

    def doCheck(): Future[T] = {
      block.recoverWith {
        case recheck if checkUntil > System.currentTimeMillis() =>
          val timeout = Promise[T]()
          server.application.actorSystem.scheduler.scheduleOnce(checkEvery) {
            timeout.completeWith(doCheck())
          }(server.executionContext)
          timeout.future
      }
    }

    doCheck()
  }
}