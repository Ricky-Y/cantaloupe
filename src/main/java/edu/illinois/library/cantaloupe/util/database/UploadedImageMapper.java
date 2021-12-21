package edu.illinois.library.cantaloupe.util.database;

import java.sql.ResultSet;
import java.sql.SQLException;

public class UploadedImageMapper {
  public static UploadedImage map(ResultSet resultSet) throws SQLException {
    if (!resultSet.next()) {
      return null;
    }

    UploadedImage image = new UploadedImage();
    image.setFilename(resultSet.getString("filename"));
    image.setExtension(resultSet.getString("extension"));
    image.setLength(resultSet.getInt("length"));
    image.setHashValue(resultSet.getString("hash_value"));

    return image;
  }
}
