package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaUploadObjects
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    if (event.getBody() != null && event.getBody().contains("warmer")) {
      context.getLogger().log("Warming event received. Exiting.");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody("Warmed");
    }

    String requestBody = event.getBody();

    JSONObject bodyJSON = new JSONObject(requestBody);
    String bucketName = bodyJSON.optString("bucket", ""); // Use bucket from payload
    String content = bodyJSON.getString("content");
    String objName = bodyJSON.getString("key");
    String email = bodyJSON.optString("email", "unknown");
    String description = bodyJSON.optString("description", "no description");

    // 1. Decode the Base64 image content received from the orchestrator
    byte[] objBytes = Base64.getDecoder().decode(content.getBytes());

    // 2. Map user-provided fields (email, description) to S3 metadata headers
    java.util.Map<String, String> metadata = new java.util.HashMap<>();
    metadata.put("email", email);
    metadata.put("description", description);

    // 3. Prepare and execute the S3 PutObject operation
    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(objName)
        .metadata(metadata)
        .build();

    S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_1).build();
    s3Client.putObject(putObjectRequest, RequestBody.fromBytes(objBytes));

    String message = "Object uploaded successfully";

    String encodedString = Base64.getEncoder().encodeToString(message.getBytes());

    APIGatewayProxyResponseEvent response;
    response = new APIGatewayProxyResponseEvent();
    response.setStatusCode(200);
    response.setBody(encodedString);
    response.withIsBase64Encoded(true);
    response.setHeaders(java.util.Collections.singletonMap("Content-Type", "text/plain"));

    return response;
  }
}
