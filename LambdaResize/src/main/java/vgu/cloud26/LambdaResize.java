package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaResize
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RESIZED_BUCKET_NAME = "";
  private static final float MAX_DIMENSION = 100;
  private final String REGEX = ".*\\.([^\\.]*)";
  private final String JPG_TYPE = "jpg";
  private final String JPG_MIME = "image/jpeg";
  private final String PNG_TYPE = "png";
  private final String PNG_MIME = "image/png";

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
    LambdaLogger logger = context.getLogger();
    logger.log("Resize Worker Started (Restored)");

    try {
      // 1. Get request body and extract image metadata
      String requestBody = event.getBody();
      if (requestBody == null) {
        return createResponse(400, "Error: Request body is missing.");
      }

      JSONObject bodyJSON = new JSONObject(requestBody);
      String originalKey = bodyJSON.optString("key", null);
      String contentBase64 = bodyJSON.optString("content", null);

      if (originalKey == null || contentBase64 == null) {
        logger.log("Missing key or content. Key: " + originalKey);
        return createResponse(400, "Error: 'key' and 'content' are required.");
      }

      logger.log("Resizing file: " + originalKey);
      String dstKey = "resized-" + originalKey;

      // Use regex to extract file extension for ImageIO
      Matcher matcher = Pattern.compile(REGEX).matcher(originalKey);
      if (!matcher.matches()) {
        logger.log("Pattern match failed for: " + originalKey);
        return createResponse(400, "Error: Unable to infer type for " + originalKey);
      }
      String imageType = matcher.group(1).toLowerCase();
      // Normalize image type for ImageIO.write
      if (imageType.equals("jpeg"))
        imageType = "jpg";

      logger.log("Detected image type: " + imageType);

      byte[] imageBytes = Base64.getDecoder().decode(contentBase64);
      logger.log("Payload size: " + imageBytes.length + " bytes");

      BufferedImage srcImage = null;
      try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
        srcImage = ImageIO.read(bais);
      }

      if (srcImage == null) {
        logger.log("ImageIO.read returned null for " + originalKey + ". Format might not be supported.");
        return createResponse(400, "Error: Could not read image. Format might not be supported.");
      }

      logger.log("Source dimensions: " + srcImage.getWidth() + "x" + srcImage.getHeight());
      // Perform the actual resize operation
      BufferedImage newImage = resizeImage(srcImage);
      logger.log("Resized dimensions: " + newImage.getWidth() + "x" + newImage.getHeight());

      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      boolean writeSuccess = ImageIO.write(newImage, imageType, outputStream);

      if (!writeSuccess) {
        logger.log("ImageIO.write failed for type: " + imageType + ". Retrying with 'png'.");
        outputStream.reset();
        ImageIO.write(newImage, "png", outputStream);
      }

      byte[] resizedBytes = outputStream.toByteArray();
      logger.log("Resized payload size: " + resizedBytes.length + " bytes");

      S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_1).build();
      uploadToS3(s3Client, resizedBytes, RESIZED_BUCKET_NAME, dstKey, imageType);

      logger.log("Successfully uploaded to S3: " + dstKey);
      return createResponse(200, "Success: Resized and uploaded " + dstKey);

    } catch (Throwable e) {
      logger.log("CRITICAL EXCEPTION: " + e.toString());
      e.printStackTrace();
      return createResponse(500, "Error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
    }
  }

  private void uploadToS3(S3Client s3Client, byte[] data, String bucket, String key, String imageType) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("Content-Length", Integer.toString(data.length));
    if (JPG_TYPE.equals(imageType))
      metadata.put("Content-Type", JPG_MIME);
    else if (PNG_TYPE.equals(imageType))
      metadata.put("Content-Type", PNG_MIME);

    PutObjectRequest putRequest = PutObjectRequest.builder().bucket(bucket).key(key).metadata(metadata).build();
    s3Client.putObject(putRequest, RequestBody.fromBytes(data));
  }

  // Scale image down while maintaining aspect ratio, capped at MAX_DIMENSION
  private BufferedImage resizeImage(BufferedImage srcImage) {
    int srcHeight = srcImage.getHeight();
    int srcWidth = srcImage.getWidth();
    float scalingFactor = Math.min(MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
    int width = (int) (scalingFactor * srcWidth);
    int height = (int) (scalingFactor * srcHeight);

    BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = resizedImage.createGraphics();
    graphics.setPaint(Color.white);
    graphics.fillRect(0, 0, width, height);
    // Use Bilinear interpolation for better quality
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    graphics.drawImage(srcImage, 0, 0, width, height, null);
    graphics.dispose();
    return resizedImage;
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withBody(message).withIsBase64Encoded(false);
  }
}
