package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

public class LambdaDeleteResizedObject
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Update with your actual Resized Bucket Name
    private static final String RESIZED_BUCKET_NAME = "";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        // --- WARMER CHECK (Now works because variable is 'event') ---
        if (event.getBody() != null && event.getBody().contains("warmer")) {
            context.getLogger().log("Warming event received. Exiting.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Warmed");
        }
        // ------------------------------------------------------------

        LambdaLogger logger = context.getLogger();

        try {
            // 1. Parse Input
            String requestBody = event.getBody();
            logger.log("Received payload in Resized worker: " + requestBody);

            JSONObject bodyJSON = new JSONObject(requestBody);
            String originalKey = bodyJSON.getString("key");

            // 2. Derive the resized object name (prefixed with 'resized-')
            String resizedKey = "resized-" + originalKey;
            logger.log("Attempting to delete from: " + RESIZED_BUCKET_NAME + " Key: " + resizedKey);

            // 3. Delete the thumbnail from the dedicated resize bucket
            S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_1).build();

            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(RESIZED_BUCKET_NAME)
                    .key(resizedKey)
                    .build();

            s3Client.deleteObject(deleteRequest);

            logger.log("Successfully sent delete request for: " + resizedKey);
            return createResponse(200, "Success: Deleted " + resizedKey);

        } catch (Exception e) {
            logger.log("Error in Resized worker: " + e.toString());
            return createResponse(500, "Error in Resized worker: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(message)
                .withIsBase64Encoded(false);
    }
}
