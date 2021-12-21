package edu.illinois.library.cantaloupe.util.database;

import com.zaxxer.hikari.HikariDataSource;
import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class DatabaseUtils {
  private static HikariDataSource dataSource;
  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseUtils.class);

  public static Optional<UploadedImage> getImageByHashExtensionAndLength(String hashValue, Integer fileLength, String extension) throws SQLException {
    try (Connection connection = getConnection()) {
      String sql = "SELECT * FROM uploaded_images where hash_value = ? AND length = ? AND extension = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, hashValue);
        statement.setInt(2, fileLength);
        statement.setString(3, extension);

        ResultSet resultSet = statement.executeQuery();
        return Optional.ofNullable(UploadedImageMapper.map(resultSet));
      }
    }
  }

  public static Integer insertImageRecord(UploadedImage image) throws SQLException {
    try (Connection connection = getConnection()) {
      String sql = "INSERT INTO uploaded_images (filename, extension, length, hash_value) VALUES (?, ?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, image.getFilename());
        statement.setString(2, image.getExtension());
        statement.setInt(3, image.getLength());
        statement.setString(4, image.getHashValue());

        return statement.executeUpdate();
      }
    }
  }

  public static Integer deleteImageRecord(UploadedImage image) throws SQLException {
    try (Connection connection = getConnection()) {
      String sql = "DELETE FROM uploaded_images where hash_value = ? AND length = ? AND extension = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, image.getHashValue());
        statement.setInt(2, image.getLength());
        statement.setString(3, image.getExtension());

        return statement.executeUpdate();
      }
    }
  }

  private synchronized static Connection getConnection() throws SQLException {
    if (dataSource == null) {
      final Configuration config = Configuration.getInstance();

      final String connectionString = config.getString(Key.DATABASE_URL, "");
      final String user = config.getString(Key.DATABASE_USERNAME, "");
      final String password = config.getString(Key.DATABASE_PASSWORD, "");

      dataSource = new HikariDataSource();
      dataSource.setJdbcUrl(connectionString);
      dataSource.setUsername(user);
      dataSource.setPassword(password);
      dataSource.setPoolName(DatabaseUtils.class.getSimpleName() + "Pool");
    }

    return dataSource.getConnection();
  }

  public synchronized static void close() {
    synchronized (DatabaseUtils.class) {
      if (dataSource != null) {
        dataSource.close();
        dataSource = null;
      }
    }
  }
}
