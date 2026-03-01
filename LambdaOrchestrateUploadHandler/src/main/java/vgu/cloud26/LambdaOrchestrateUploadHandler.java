package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaOrchestrateUploadHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;

  public LambdaOrchestrateUploadHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_1).build();
  }

  // Generic helper to invoke a worker Lambda and parse its JSON response
  public String callLambda(String functionName, String payload, LambdaLogger logger) {
    try {
      InvokeRequest invokeRequest = InvokeRequest.builder()
          .functionName(functionName)
          .payload(SdkBytes.fromUtf8String(payload))
          .invocationType("RequestResponse") // Wait for worker to finish
          .build();

      InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
      ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
      String jsonResponse = StandardCharsets.UTF_8.decode(responsePayload).toString();

      // Attempt to extract the "body" field if the worker returned a standard proxy
      // response
      try {
        JSONObject responseObject = new JSONObject(jsonResponse);
        if (responseObject.has("body")) {
          return responseObject.getString("body");
        }
        return jsonResponse;
      } catch (Exception jsonEx) {
        logger.log("Worker returned non-JSON response: " + jsonResponse);
        return jsonResponse; // Fallback to raw string
      }
    } catch (Exception e) {
      logger.log("Error invoking " + functionName + ": " + e.getMessage());
      return "Failed: " + e.getMessage();
    }
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    try {
      if (event.getBody() != null && event.getBody().contains("warmer")) {
        context.getLogger().log("Warming event received. Exiting.");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody("Warmed");
      }

      LambdaLogger logger = context.getLogger();
      String userRequestBody = event.getBody();
      if (userRequestBody == null) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withBody("{\"error\": \"Empty request body\"}");
      }

      // 1. Prepare Payload
      JSONObject bodyJSON = new JSONObject(userRequestBody);

      // Explicitly adding 'bucket' to match your requested workflow
      bodyJSON.put("bucket", "");

      JSONObject workerPayloadJson = new JSONObject();
      workerPayloadJson.put("body", bodyJSON.toString());
      String downstreamPayload = workerPayloadJson.toString();

      // 2. Execute Orchestrated Activities
      JSONObject results = new JSONObject();

      try {
        // Step 1: Record metadata in the MySQL Database
        logger.log("Activity 1: DB Insert");
        String dbResult = callLambda("BisLambdaAddPhotoDB", downstreamPayload, logger);
        results.put("Activity_1_Database", dbResult);

        // Step 2: Store original file in high-performance S3 bucket
        logger.log("Activity 2: Original Upload");
        String originalResult = callLambda("BisLambdaUpload", downstreamPayload, logger);
        results.put("Activity_2_Original_S3", originalResult);

        // Step 3: Trigger image processing / thumbnail creation
        logger.log("Activity 3: Resize Upload");
        String resizeResult = callLambda("LambdaResize", downstreamPayload, logger);
        results.put("Activity_3_Resize_S3", resizeResult);

      } catch (Exception e) {
        logger.log("Orchestration Activity Error: " + e.getMessage());
        results.put("ORCHESTRATION_ACTIVITY_ERROR", e.getMessage());
      }

      // 3. Return Combined Report
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(Map.of("Content-Type", "application/json"))
          .withBody(results.toString(4)) // Pretty print JSON
          .withIsBase64Encoded(false);

    } catch (Throwable t) {
      // LAST RESORT CATCH: If anything crashes, return it as JSON instead of a 502
      JSONObject errorJson = new JSONObject();
      errorJson.put("error", "Orchestrator Internal Crash");
      errorJson.put("details", t.toString());
      errorJson.put("stackTrace", java.util.Arrays.toString(t.getStackTrace()));

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withHeaders(Map.of("Content-Type", "application/json"))
          .withBody(errorJson.toString())
          .withIsBase64Encoded(false);
    }
  }
}
