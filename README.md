# 🍈 Cantaloupe

*This is README for SuperStar developers, original readme refers to [here](Original_README.md)*

*There is a project website [here](https://cantaloupe-project.github.io/), refer to it for more info* 

## Developers

The codebase is based on an open source project, [Cantaloupe](https://github.com/cantaloupe-project/cantaloupe). But some customizations are done:
* Add an endpoint for image uploading
* Uploaded image is converted to pyramidal tiff format to allow fetching by tile
* Support two data storages: superstar CDN and SSD mounted on servers via network and  it is configurable
* For mounted SSD storage, deduplicate images by size, format and hash value (CDN has its own dedup function)
* Allow fetching image by filename from mounted SSD or by object id from CDN

### Setting up local machine

To use `FileSystem` as the image storage, a database is required. Create a database named `cantaloupe` and run this SQL statement to create a table:

```
create table uploaded_images (
  id int unsigned NOT NULL AUTO_INCREMENT,
  filename varchar(70) NOT NULL,
  extension varchar(4) NOT NULL,
  length int unsigned NOT NULL,
  hash_value char(32) NOT NULL,
  upload_time timestamp DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY `index_on_program_id_network_and_country_code_and_merchant_name` (`program_id`, `network`, `country_code`, `merchant_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
```

### Build & run

First of all, a configuration file must be created. Two templates are provided in this repo: `cantaloupe.properties.sample` and `cantaloupe.production.properties.sample`, for development and production environment, respectively.

Copy example configuration files and remove `.example` to make them work and change configurations accordingly.

Little trick, in linux/unix env, run `cp cantaloupe.properties{,.sample}` to do it.

#### Command line

* `mvn clean compile exec:java -Dcantaloupe.config=...` will build and run the
  project using the embedded web server listening on the port(s) specified in
  `cantaloupe.properties`.
* `mvn clean package -DskipTests` will build a release JAR in the `target`
  folder, which can be run via:

  `java -jar cantaloupe-5.0.5.jar -Dcantaloupe.config=/path/to/cantaloupe.properties`

#### IDE

1. Add a new run configuration using the "Maven" template.
2. In `parameters` tab, set command line to `exec:java -Dcantaloupe.config=cantaloupe.properties`.

## Deploy

1. Build the jar file locally
2. Upload the jar file to server (`scp` if you are on unix/linux machine)
3. Stop the running application and remove the outdated jar file.
4. Move uploaded jar to the working directory and start the application.

