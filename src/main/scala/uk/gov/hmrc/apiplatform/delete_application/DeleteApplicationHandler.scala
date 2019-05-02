package uk.gov.hmrc.apiplatform.delete_application

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.AwsIdRetriever
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.{JsonMapper, SqsHandler}

import scala.collection.JavaConverters._

class DeleteApplicationHandler(override val apiGatewayClient: ApiGatewayClient) extends SqsHandler with AwsIdRetriever with JsonMapper {

  def this() {
    this(awsApiGatewayClient)
  }

  override def handleInput(event: SQSEvent, context: Context): Unit = {
    val logger = context.getLogger

    if (event.getRecords.size != 1) {
      throw new IllegalArgumentException(s"Invalid number of records: ${event.getRecords.size}")
    }

    val application = fromJson[Application](event.getRecords.get(0).getBody)
    getAwsUsagePlanIdByApplicationName(application.applicationName) match {
      case Some(usagePlanId) => deleteUsagePlan(usagePlanId)
      case None => logger.log(s"Usage plan with name ${application.applicationName} not found")
    }
  }

  private def deleteUsagePlan(usagePlanId: String): Unit = {
    apiGatewayClient.getUsagePlanKeys(GetUsagePlanKeysRequest.builder().usagePlanId(usagePlanId).build())
      .items().asScala
      .foreach(apiKey => deleteApiKeys(apiKey.id(), usagePlanId))
    apiGatewayClient.deleteUsagePlan(DeleteUsagePlanRequest.builder().usagePlanId(usagePlanId).build())
  }

  private def deleteApiKeys(apiKeyId: String, usagePlanId: String): Unit = {
    apiGatewayClient.deleteUsagePlanKey(DeleteUsagePlanKeyRequest.builder().keyId(apiKeyId).usagePlanId(usagePlanId).build())
    apiGatewayClient.deleteApiKey(DeleteApiKeyRequest.builder().apiKey(apiKeyId).build())
  }
}

case class Application(applicationName: String)
