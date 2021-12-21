package edu.illinois.library.cantaloupe.util.database;

public class UploadedImage {
  private String filename;
  private String extension;
  private Integer length;
  private String hashValue;

  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

  public String getExtension() {
    return extension;
  }

  public void setExtension(String extension) {
    this.extension = extension;
  }

  public Integer getLength() {
    return length;
  }

  public void setLength(Integer length) {
    this.length = length;
  }

  public String getHashValue() {
    return hashValue;
  }

  public void setHashValue(String hashValue) {
    this.hashValue = hashValue;
  }
}
