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

public class LambdaOrchestrateDeleteHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;

    public LambdaOrchestrateDeleteHandler() {
        this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_1).build();
    }

    // Helper to invoke worker Lambdas
    public String callLambda(String functionName, String payload, LambdaLogger logger) {
        try {
            InvokeRequest invokeRequest = InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromUtf8String(payload))
                    .invocationType("RequestResponse") // Synchronous
                    .build();

            InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
            ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
            String jsonResponse = StandardCharsets.UTF_8.decode(responsePayload).toString();

            // Catch parsing errors specifically to handle non-JSON responses
            try {
                JSONObject responseObject = new JSONObject(jsonResponse);
                if (responseObject.has("body")) {
                    return responseObject.getString("body");
                }
                return jsonResponse;
            } catch (Exception jsonEx) {
                logger.log("Worker returned non-JSON response: " + jsonResponse);
                return jsonResponse; // Return as-is if not JSON
            }
        } catch (Exception e) {
            logger.log("Error invoking " + functionName + ": " + e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            if (event.getBody() != null && event.getBody().contains("warmer")) {
                context.getLogger().log("Warming event received. Exiting.");
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody("Warmed");
            }
            LambdaLogger logger = context.getLogger();
            logger.log("Starting Delete Orchestration...");

            // 1. Get Key from User Request
            String userRequestBody = event.getBody();
            if (userRequestBody == null) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"error\": \"Empty request body\"}");
            }

            // 2. Wrap payload for workers
            JSONObject workerPayloadJson = new JSONObject();
            workerPayloadJson.put("body", userRequestBody);
            String downstreamPayload = workerPayloadJson.toString();

            // 3. Execute Activities
            JSONObject results = new JSONObject();

            try {
                // Activity 1: Delete from DB (The worker handles ownership verification)
                logger.log("Activity 1: Deleting from DB");
                String dbResult = callLambda("BisLambdaDeletePhotoDB", downstreamPayload, logger);
                results.put("Activity_1_DB_Delete", dbResult);
                logger.log("DB Result: " + dbResult);

                // IMPORTANT: Abort orchestration if the user doesn't own the file or another
                // error occurs
                if (dbResult.toLowerCase().contains("error") ||
                        dbResult.contains("Forbidden") ||
                        dbResult.contains("Failed")) {
                    logger.log("ABORTING: Activity 1 failed or forbidden.");
                    return new APIGatewayProxyResponseEvent()
                            .withStatusCode(403)
                            .withHeaders(Map.of("Content-Type", "application/json"))
                            .withBody(results.toString(4))
                            .withIsBase64Encoded(false);
                }

                // Activity 2: Delete Original image from S3
                logger.log("Activity 2: Deleting Original S3");
                String originalResult = callLambda("BisLambdaDeleteObjects", downstreamPayload, logger);
                results.put("Activity_2_Original_Delete", originalResult);

                // Activity 3: Delete the Resized (thumbnail) image from S3
                logger.log("Activity 3: Deleting Resized S3");
                String resizedResult = callLambda("BisLambdaDeleteResizedObject", downstreamPayload, logger);
                results.put("Activity_3_Resized_Delete", resizedResult);

            } catch (Exception e) {
                logger.log("Orchestration Logic Error: " + e.getMessage());
                results.put("ORCHESTRATION_LOGIC_ERROR", e.getMessage());
            }

            // 4. Return Combined Report
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(results.toString(4))
                    .withIsBase64Encoded(false);

        } catch (Throwable t) {
            JSONObject errorJson = new JSONObject();
            errorJson.put("error", "Delete Orchestrator Crash");
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
