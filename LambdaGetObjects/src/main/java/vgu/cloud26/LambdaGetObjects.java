package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class LambdaGetObjects
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {

    // --- WARMER CHECK ---
    if (event.getBody() != null && event.getBody().contains("warmer")) {
      context.getLogger().log("Warming event received. Exiting.");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody("Warmed");
    }
    // --------------------

    try {
      // 1. Parse the request body to extract the S3 'key'
      String requestBody = event.getBody();
      if (requestBody == null || requestBody.isEmpty()) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withBody("Missing request body");
      }

      JSONObject bodyJSON = new JSONObject(requestBody);
      String key = bodyJSON.getString("key");

      if (key == null || key.isEmpty()) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(400)
            .withBody("Missing 'key' in body");
      }

      // 2. Fetch the specific object content from S3
      String bucketName = "";
      S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_1).build();

      GetObjectRequest getObjectRequest = GetObjectRequest.builder()
          .bucket(bucketName)
          .key(key)
          .build();

      ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
      byte[] data = objectBytes.asByteArray();
      String contentType = objectBytes.response().contentType();

      // 3. Return the bytes encoded as Base64
      // APIGateway will decode this back to binary for the browser if isBase64Encoded
      // is true
      String base64Data = Base64.getEncoder().encodeToString(data);

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(Map.of("Content-Type", contentType))
          .withBody(base64Data)
          .withIsBase64Encoded(true);

    } catch (Exception e) {
      context.getLogger().log("Error fetching object: " + e.toString());
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody("Error: " + e.getMessage());
    }
  }
}
