package uk.gov.hmrc.apiplatform.delete_application

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import com.amazonaws.services.lambda.runtime.{Context, LambdaLogger}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.JsonMapper

import scala.collection.JavaConversions.seqAsJavaList
import scala.collection.JavaConverters._

class DeleteApplicationHandlerSpec extends WordSpecLike with Matchers with MockitoSugar with JsonMapper {

  trait Setup {
    val applicationName = "foo-app"
    val usagePlanId: String = UUID.randomUUID().toString
    val apiKeyId: String = UUID.randomUUID().toString

    val requestBody = s"""{"applicationName": "$applicationName"}"""
    val message = new SQSMessage()
    message.setBody(requestBody)
    val sqsEvent = new SQSEvent()
    sqsEvent.setRecords(List(message))

    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val deleteApplicationHandler = new DeleteApplicationHandler(mockAPIGatewayClient)
    val mockContext: Context = mock[Context]
    when(mockContext.getLogger).thenReturn(mock[LambdaLogger])
  }

  "Delete Application Handler" should {
    "delete the usage plan and API key from API Gateway when found" in new Setup {
      when(mockAPIGatewayClient.getUsagePlans(any[GetUsagePlansRequest])).thenReturn(buildMatchingUsagePlansResponse(usagePlanId, applicationName))
      when(mockAPIGatewayClient.getUsagePlanKeys(any[GetUsagePlanKeysRequest])).thenReturn(GetUsagePlanKeysResponse.builder().items(UsagePlanKey.builder().id(apiKeyId).build()).build())
      val deleteUsagePlanKeyRequestCaptor: ArgumentCaptor[DeleteUsagePlanKeyRequest] = ArgumentCaptor.forClass(classOf[DeleteUsagePlanKeyRequest])
      when(mockAPIGatewayClient.deleteUsagePlanKey(deleteUsagePlanKeyRequestCaptor.capture())).thenReturn(DeleteUsagePlanKeyResponse.builder().build())
      val deleteApiKeyCaptor: ArgumentCaptor[DeleteApiKeyRequest] = ArgumentCaptor.forClass(classOf[DeleteApiKeyRequest])
      when(mockAPIGatewayClient.deleteApiKey(deleteApiKeyCaptor.capture())).thenReturn(DeleteApiKeyResponse.builder().build())
      val deleteUsagePlanRequestCaptor: ArgumentCaptor[DeleteUsagePlanRequest] = ArgumentCaptor.forClass(classOf[DeleteUsagePlanRequest])
      when(mockAPIGatewayClient.deleteUsagePlan(deleteUsagePlanRequestCaptor.capture())).thenReturn(DeleteUsagePlanResponse.builder().build())

      deleteApplicationHandler.handleInput(sqsEvent, mockContext)

      deleteUsagePlanKeyRequestCaptor.getValue.keyId shouldEqual apiKeyId
      deleteUsagePlanKeyRequestCaptor.getValue.usagePlanId shouldEqual usagePlanId
      deleteApiKeyCaptor.getValue.apiKey shouldEqual apiKeyId
      deleteUsagePlanRequestCaptor.getValue.usagePlanId shouldEqual usagePlanId
    }

    "not do anything when the application is not found" in new Setup {
      when(mockAPIGatewayClient.getUsagePlans(any[GetUsagePlansRequest])).thenReturn(buildNonMatchingUsagePlansResponse(1))

      deleteApplicationHandler.handleInput(sqsEvent, mockContext)

      verify(mockAPIGatewayClient, times(0)).deleteUsagePlanKey(any[DeleteUsagePlanKeyRequest])
      verify(mockAPIGatewayClient, times(0)).deleteApiKey(any[DeleteApiKeyRequest])
      verify(mockAPIGatewayClient, times(0)).deleteUsagePlan(any[DeleteUsagePlanRequest])
    }

    "throw an Exception if multiple messages have been retrieved from SQS" in new Setup {
      sqsEvent.setRecords(List(message, message))

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](deleteApplicationHandler.handleInput(sqsEvent, mockContext))

      exception.getMessage shouldEqual "Invalid number of records: 2"
    }

    "throw an Exception if no messages have been retrieved from SQS" in new Setup {
      sqsEvent.setRecords(List())

      val exception: IllegalArgumentException = intercept[IllegalArgumentException](deleteApplicationHandler.handleInput(sqsEvent, mockContext))

      exception.getMessage shouldEqual "Invalid number of records: 0"
    }

    "propagate any exceptions thrown by SDK" in new Setup {
      when(mockAPIGatewayClient.getUsagePlans(any[GetUsagePlansRequest])).thenReturn(buildMatchingUsagePlansResponse(usagePlanId, applicationName))
      when(mockAPIGatewayClient.getUsagePlanKeys(any[GetUsagePlanKeysRequest])).thenReturn(GetUsagePlanKeysResponse.builder().items(UsagePlanKey.builder().id(apiKeyId).build()).build())

      val errorMessage = "Unauthorized!"
      when(mockAPIGatewayClient.deleteUsagePlan(any[DeleteUsagePlanRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val exception: UnauthorizedException = intercept[UnauthorizedException](deleteApplicationHandler.handleInput(sqsEvent, mockContext))

      exception.getMessage shouldEqual errorMessage
    }
  }

  def buildMatchingUsagePlansResponse(matchingId: String, matchingName: String): GetUsagePlansResponse = {
    GetUsagePlansResponse.builder()
      .items(UsagePlan.builder().id(matchingId).name(matchingName).build())
      .build()
  }

  def buildNonMatchingUsagePlansResponse(count: Int): GetUsagePlansResponse = {
    val items = (1 to count).map(c => UsagePlan.builder().id(s"$c").name(s"Item $c").build())

    GetUsagePlansResponse.builder()
      .items(items.asJava)
      .build()
  }
}
