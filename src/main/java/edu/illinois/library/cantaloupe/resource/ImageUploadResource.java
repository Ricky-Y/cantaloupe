package edu.illinois.library.cantaloupe.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.http.Method;
import edu.illinois.library.cantaloupe.util.database.DatabaseUtils;
import edu.illinois.library.cantaloupe.util.database.UploadedImage;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import okhttp3.*;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;


public class ImageUploadResource extends PublicResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImageUploadResource.class);
  private static final Method[] SUPPORTED_METHODS = new Method[]{Method.POST, Method.OPTIONS};
  private static final String GENERATE_PYRAMID_TIFF_COMMAND = "vips tiffsave %s %s --tile --pyramid --compression deflate --tile-width 256 --tile-height 256\n";
  private static final String CDN_UPLOAD_ENDPOINT = "http://cs.ananas.chaoxing.com/upload";
  private static final String UID = "189943383";
  private static final String PRODUCT_ID = "4";
  private static final String CLIENT_IP = "127.0.0.1";
  private MessageDigest algorithm;

  @Override
  protected Logger getLogger() {
    return LOGGER;
  }

  @Override
  public Method[] getSupportedMethods() {
    return SUPPORTED_METHODS;
  }

  @Override
  public void doPOST() throws Exception {
    Path filePath = null;
    String tiffFileName = null;

    try {
      filePath = storeUploadedFileTemporarily();
      tiffFileName = convertImageToPyramidalTiff(filePath);
      saveFileToFinalDestination(filePath, tiffFileName);
    } catch (UploadFileFormatException e) {
      HashMap<String, String> bodyMap = new HashMap<>();
      bodyMap.put("error", e.getMessage());
      generateResponse(400, bodyMap);
    } catch (Exception e) {
      HashMap<String, String> bodyMap = new HashMap<>();
      bodyMap.put("error", e.getMessage());
      generateResponse(500, bodyMap);
    } finally {
      cleanUpTemporaryFolder(filePath, tiffFileName);
    }
  }

  private Path storeUploadedFileTemporarily() throws ServletException, IOException, UploadFileFormatException, NoSuchAlgorithmException {
    Part imagePart = getRequest().getServletRequest().getPart("image");
    String name = getFileName(imagePart);
    algorithm = MessageDigest.getInstance("MD5");
    try (InputStream imageStream = imagePart.getInputStream();
         DigestInputStream dis = new DigestInputStream(imageStream, algorithm)) {
      String tempFolderPath = getConfiguration().getString(Key.FILESYSTEMSOURCE_TEMPORARY_FOLDER);
      Path filePath = Paths.get(tempFolderPath + name);
      Files.copy(dis, filePath);

      return filePath;
    }
  }

  private String getFileName(Part imagePart) throws UploadFileFormatException {
    long timestamp = new Timestamp(System.currentTimeMillis()).getTime();
    String submittedFileName = imagePart.getSubmittedFileName();
    String[] splitFileName = submittedFileName.split("\\.");
    if (splitFileName.length < 2) {
      throw new UploadFileFormatException("Uploaded file has no extension");
    }
    String lastPartOfFileBaseName = splitFileName[splitFileName.length - 2];
    splitFileName[splitFileName.length - 2] = String.join("-", lastPartOfFileBaseName, String.valueOf(timestamp));

    return String.join(".", splitFileName);
  }

  private String convertImageToPyramidalTiff(Path filePath) throws IOException, InterruptedException {
    File tempDirectory = new File(getConfiguration().getString(Key.FILESYSTEMSOURCE_TEMPORARY_FOLDER));

    String savedFileName = filePath.getFileName().toString();
    String[] splitFileName = savedFileName.split("\\.");
    splitFileName[splitFileName.length - 1] = "tiff";
    String outputFileName = String.join(".", splitFileName);

    List<String> command = buildCommandStringList(savedFileName, outputFileName);
    ProcessBuilder processBuilder = new ProcessBuilder()
      .directory(tempDirectory)
      .command(command)
      .redirectErrorStream(true);

    Process process = processBuilder.start();

    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    String line;
    while ((line = reader.readLine()) != null) {
      LOGGER.info("[Executing vips command] ", line);
    }

    process.waitFor();
    return outputFileName;
  }

  private void cleanUpTemporaryFolder(Path uploadedFilePath, String tiffFileName) {
    List<Path> pathsToRemove = new ArrayList<>();
    String tempDirectory = getConfiguration().getString(Key.FILESYSTEMSOURCE_TEMPORARY_FOLDER);

    if (uploadedFilePath != null) {
      String savedFileName = uploadedFilePath.getFileName().toString();
      Path uploadedImagePath = Paths.get(tempDirectory + savedFileName);
      pathsToRemove.add(uploadedImagePath);
    }

    if (tiffFileName != null) {
      Path tiffFilePath = Paths.get(tempDirectory + tiffFileName);
      pathsToRemove.add(tiffFilePath);
    }

    for (var path : pathsToRemove) {
      try {
        Files.delete(path);
      } catch (IOException e) {
        LOGGER.warn("Failed deleting " + path, e);
      }
    }
  }

  private List<String> buildCommandStringList(String inputFile, String outputFile) {
    String s = String.format(GENERATE_PYRAMID_TIFF_COMMAND, inputFile, outputFile);
    return List.of(s.split(" "));
  }

  private void saveFileToFinalDestination(Path filePath, String tiffFileName) throws IOException, SQLException {
    if (getConfiguration().getString(Key.SOURCE_STATIC) == "HttpSource") {
      Response response = uploadPyramidalTiffToCDN(tiffFileName);
      processCDNResponse(response);

      return;
    }

    saveTiffToDisk(filePath, tiffFileName);
  }

  private void saveTiffToDisk(Path filePath, String tiffFileName) throws SQLException, IOException {
    UploadedImage image = buildUploadImageObject(filePath, tiffFileName);
    if (!tryInsertImageRecord(image)) {
      return;
    }

    Path savedPath;
    try {
      savedPath = moveTIFFToMountedDrive(tiffFileName);
    } catch (IOException e) {
      LOGGER.error("Failed copying tiff image to destination. ", e);
      DatabaseUtils.deleteImageRecord(image);
      return;
    }

    HashMap<String, String> bodyMap = new HashMap<>();
    bodyMap.put("filename", savedPath.getFileName().toString());
    generateResponse(200, bodyMap);
  }

  private boolean tryInsertImageRecord(UploadedImage image) throws SQLException, IOException {
    try{
      DatabaseUtils.insertImageRecord(image);
    } catch (SQLException e) {
      Optional<UploadedImage> uploadedImage = DatabaseUtils.getImageByHashExtensionAndLength(image.getHashValue(), image.getLength(), image.getExtension());
      if (uploadedImage.isPresent()) {
        HashMap<String, String> bodyMap = new HashMap<>();
        bodyMap.put("filename", uploadedImage.get().getFilename());
        generateResponse(200, bodyMap);
        return false;
      }

      throw e;
    }
    return true;
  }

  private UploadedImage buildUploadImageObject(Path filePath, String tiffFileName) {
    byte[] digest = algorithm.digest();
    String digestString = DatatypeConverter.printHexBinary(digest);

    File file = filePath.toFile();
    Integer fileLength = Math.toIntExact(file.length());

    String[] splitFileName = file.getName().split("\\.");
    String extension = splitFileName[splitFileName.length - 1];

    UploadedImage image = new UploadedImage();
    image.setFilename(tiffFileName);
    image.setHashValue(digestString);
    image.setLength(fileLength);
    image.setExtension(extension);
    return image;
  }

  private Path moveTIFFToMountedDrive(String tiffFileName) throws IOException {
    String destinationFolder = getConfiguration().getString(Key.FILESYSTEMSOURCE_PATH_PREFIX);
    Path destinationFilePath = Paths.get(destinationFolder + tiffFileName);
    String tempFolderPath = getConfiguration().getString(Key.FILESYSTEMSOURCE_TEMPORARY_FOLDER);
    Path tiffFilePath = Paths.get(tempFolderPath + tiffFileName);

    Files.copy(tiffFilePath, destinationFilePath);
    return destinationFilePath;
  }

  private Response uploadPyramidalTiffToCDN(String outputFileName) throws IOException {
    String tempFolderPath = getConfiguration().getString(Key.FILESYSTEMSOURCE_TEMPORARY_FOLDER);
    File outputFile = new File(tempFolderPath + outputFileName);

    RequestBody body = RequestBody.create(MediaType.parse("image/tiff"), outputFile);
    MultipartBody multipartBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("file", "output.tif", body)
      .build();

    Request request = buildCDNUploadRequest(multipartBody);

    OkHttpClient client = new OkHttpClient.Builder().build();
    return client.newCall(request).execute();
  }

  private Request buildCDNUploadRequest(MultipartBody multipartBody) {
    HttpUrl httpUrl = HttpUrl.parse(CDN_UPLOAD_ENDPOINT).newBuilder()
      .addQueryParameter("uid", UID)
      .addQueryParameter("prdid", PRODUCT_ID)
      .addQueryParameter("clientip", CLIENT_IP)
      .build();
    Request request = new Request.Builder()
      .url(httpUrl)
      .post(multipartBody)
      .build();
    return request;
  }

  private void processCDNResponse(Response cdnResponse) throws IOException {
    try (ResponseBody responseBody = cdnResponse.body()) {
      JsonNode responseBodyNode = new ObjectMapper().readTree(responseBody.string());

      if (!cdnResponse.isSuccessful()) {
        HashMap<String, String> bodyMap = new HashMap<>();
        bodyMap.put("message", "Uploading to CDN failed!");
        JsonNode error = responseBodyNode.get("error");
        if (error != null) {
          bodyMap.put("error", error.asText());
        }

        generateResponse(cdnResponse.code(), bodyMap);
        return;
      }

      String savedFileName = responseBodyNode.get("filename").asText();
      String objectId = responseBodyNode.get("objectid").asText();
      HashMap<String, String> bodyMap = new HashMap<>();
      bodyMap.put("filename", savedFileName);
      bodyMap.put("objectid", objectId);
      generateResponse(200, bodyMap);
    }
  }

  private void generateResponse(int code, HashMap<String, String> bodyMap) throws IOException {
    new JacksonRepresentation(bodyMap).write(getResponse().getOutputStream());
    getResponse().setStatus(code);
  }

  private Configuration getConfiguration() {
    return Configuration.getInstance();
  }
}
