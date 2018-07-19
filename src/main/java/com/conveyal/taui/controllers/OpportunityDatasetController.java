package com.conveyal.taui.controllers;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.util.ExceptionUtils;
import com.conveyal.taui.AnalysisServerConfig;
import com.conveyal.taui.AnalysisServerException;
import com.conveyal.taui.grids.GridExporter;
import com.conveyal.taui.grids.SeamlessCensusGridExtractor;
import com.conveyal.taui.models.OpportunityDataset;
import com.conveyal.taui.persistence.Persistence;
import com.conveyal.taui.util.Jobs;
import com.conveyal.taui.util.JsonUtil;
import com.google.common.io.Files;
import com.mongodb.QueryBuilder;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static java.lang.Boolean.parseBoolean;
import static spark.Spark.*;

/**
 * Controller that handles fetching grids.
 */
public class OpportunityDatasetController {
    private static final Logger LOG = LoggerFactory.getLogger(OpportunityDatasetController.class);

    private static final AmazonS3 s3 = new AmazonS3Client();
    private static final String BUCKET = AnalysisServerConfig.gridBucket;

    private static final FileItemFactory fileItemFactory = new DiskFileItemFactory();

    /**
     * Store upload status objects
     */
    private static List<OpportunityDatasetUploadStatus> uploadStatuses = new ArrayList<>();

    private static void addStatusAndRemoveOldStatuses(OpportunityDatasetUploadStatus status) {
        uploadStatuses.add(status);
        LocalDateTime now = LocalDateTime.now();
        uploadStatuses.removeIf(s -> s.completedAt != null &&
                LocalDateTime.ofInstant(s.completedAt.toInstant(), ZoneId.systemDefault()).isBefore(now.minusDays(7))
        );
    }

    public static Collection<OpportunityDataset> getRegionDatasets(Request req, Response res) {
        return Persistence.opportunityDatasets.findPermitted(
                QueryBuilder.start("regionId").is(req.params("regionId")).get(),
                req.attribute("accessGroup")
        );
    }

    public static Object getOpportunityDataset(Request req, Response res) {
        OpportunityDataset dataset = Persistence.opportunityDatasets.findByIdFromRequestIfPermitted(req);

        String redirectText = req.queryParams("redirect");
        boolean redirect = GridExporter.checkRedirectAndFormat(redirectText, "grid");

        // TODO handle offline mode
        return GridExporter.downloadFromS3(s3, dataset.bucketName, dataset.getKey("grid"), redirect, res);
    }

    public static List<OpportunityDatasetUploadStatus> getRegionUploadStatuses(Request req, Response res) {
        String regionId = req.params("regionId");
        return uploadStatuses
                .stream()
                .filter(status -> status.regionId.equals(regionId))
                .collect(Collectors.toList());
    }

    public static boolean clearStatus(Request req, Response res) {
        String statusId = req.params("statusId");
        return uploadStatuses.removeIf(s -> s.id.equals(statusId));
    }

    /**
     * Handle many types of file upload. Returns a OpportunityDatasetUploadStatus which has a handle to request status.
     */
    public static OpportunityDatasetUploadStatus createOpportunityDataset(Request req, Response res) {
        final String accessGroup = req.attribute("accessGroup");
        final String email = req.attribute("email");

        ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
        String sourceName, regionId;
        Map<String, List<FileItem>> query;
        try {
            query = sfu.parseParameterMap(req.raw());
            sourceName = query.get("Name").get(0).getString("UTF-8");
            regionId = query.get("regionId").get(0).getString("UTF-8");
        } catch (Exception e) {
            throw AnalysisServerException.fileUpload("Unable to create opportunity dataset. " + e.getMessage());
        }

        // Set a region wide status that we are processing opportunity data
        // TODO change this into a centrally located "Region/Account Status" field.
        OpportunityDatasetUploadStatus status = new OpportunityDatasetUploadStatus(regionId, sourceName);
        addStatusAndRemoveOldStatuses(status);

        Jobs.service.submit(() -> {
            try {
                Map<String, Grid> grids = null;

                for (FileItem fi : query.get("files")) {
                    String name = fi.getName();
                    if (name.endsWith(".csv")) {
                        LOG.info("Detected opportunity dataset stored as CSV");
                        grids = createGridsFromCsv(query, status);
                        break;
                    } else if (name.endsWith(".grid")) {
                        LOG.info("Detected opportunity dataset stored in Conveyal binary format.");
                        grids = createGridsFromBinaryGridFiles(query, status);
                        break;
                    } else if (name.endsWith(".shp")) {
                        LOG.info("Detected opportunity dataset stored as shapefile");
                        grids = createGridsFromShapefile(query, fi.getName().substring(0, name.length() - 4), status);
                        break;
                    }
                }

                if (grids == null) {
                    status.status = Status.ERROR;
                    status.message = "Unable to create opportunity dataset from the files uploaded.";
                    status.completed();
                    return null;
                } else {
                    status.status = Status.UPLOADING;
                    status.totalGrids = grids.size();
                    LOG.info("Uploading opportunity dataset to S3");
                    String sourceId = UUID.randomUUID().toString();
                    grids.entrySet().forEach(entry -> {
                        Grid grid = entry.getValue();

                        double totalOpportunities = 0;
                        for (int i = 0; i < grid.grid.length; i++) {
                            for (int j = 0; j < grid.grid[i].length; j++) {
                                totalOpportunities += grid.grid[i][j];
                            }
                        }

                        // Create new opportunity dataset object
                        OpportunityDataset dataset = new OpportunityDataset();
                        dataset.bucketName = BUCKET;
                        dataset.sourceName = sourceName;
                        dataset.sourceId = sourceId;
                        dataset.name = entry.getKey();
                        dataset.createdBy = email;
                        dataset.accessGroup = accessGroup;
                        dataset.regionId = regionId;
                        dataset.north = grid.north;
                        dataset.west = grid.west;
                        dataset.width = grid.width;
                        dataset.height = grid.height;
                        dataset.totalOpportunities = totalOpportunities;

                        // Upload to S3
                        try {
                            GridExporter.writeToS3(grid, s3, dataset.bucketName, dataset.getKey("grid"), "grid");
                            status.uploadedGrids += 1;
                            if (status.uploadedGrids == status.totalGrids) {
                                status.status = Status.DONE;
                                status.completed();
                            }
                            LOG.info("Completed {}/{} uploads for {}", status.uploadedGrids, status.totalGrids, status.name);
                        } catch (IOException e) {
                            status.status = Status.ERROR;
                            status.message = ExceptionUtils.asString(e);
                            status.completed();
                            throw AnalysisServerException.unknown(e);
                        }

                        Persistence.opportunityDatasets.create(dataset);
                    });
                }
            } catch (Exception e) {
                status.status = Status.ERROR;
                status.message = ExceptionUtils.asString(e);
                status.completed();
            }

            return null;
        });

        return status;
    }

    public static OpportunityDataset editOpportunityDataset(Request request, Response response) throws IOException {
        return Persistence.opportunityDatasets.updateFromJSONRequest(request);
    }

    public static Collection<OpportunityDataset> deleteSourceSet(Request request, Response response) {
        String sourceId = request.params("sourceId");
        String accessGroup = request.attribute("accessGroup");
        Collection<OpportunityDataset> datasets = Persistence.opportunityDatasets.findPermitted(
                QueryBuilder.start("sourceId").is(sourceId).get(), accessGroup);

        datasets.forEach(dataset -> deleteDataset(dataset._id, accessGroup));

        return datasets;
    }

    public static OpportunityDataset deleteOpportunityDataset(Request request, Response response) {
        String opportunityDatasetId = request.params("_id");
        return deleteDataset(opportunityDatasetId, request.attribute("accessGroup"));
    }

    /**
     * Delete an Opportunity Dataset from the database and all formats from S3
     * @param id
     * @param accessGroup
     * @return
     */
    private static OpportunityDataset deleteDataset(String id, String accessGroup) {
        OpportunityDataset dataset = Persistence.opportunityDatasets.removeIfPermitted(id, accessGroup);

        if (dataset == null) {
            throw AnalysisServerException.notFound("Opportunity dataset could not be found.");
        } else {
            deleteFormatIfExists(dataset.bucketName, dataset.getKey("grid"));
            deleteFormatIfExists(dataset.bucketName, dataset.getKey("png"));
            deleteFormatIfExists(dataset.bucketName, dataset.getKey("tiff"));
        }

        return dataset;
    }

    /**
     * Delete the OD grid format if it exists.
     * @param bucketName
     * @param key
     */
    private static void deleteFormatIfExists (String bucketName, String key) {
        if (s3.doesObjectExist(bucketName, key)) {
            s3.deleteObject(bucketName, key);
        }
    }

    /**
     * Create a grid from WGS 84 points in a CSV file
     */
    private static Map<String, Grid> createGridsFromCsv(Map<String, List<FileItem>> query, OpportunityDatasetUploadStatus status) throws Exception {
        String latField = query.get("latField").get(0).getString("UTF-8");
        String lonField = query.get("lonField").get(0).getString("UTF-8");

        List<FileItem> file = query.get("files");

        if (file.size() != 1) {
            throw AnalysisServerException.fileUpload("CSV upload only supports one file at a time.");
        }

        // create a temp file because we have to loop over it twice
        File tempFile = File.createTempFile("grid", ".csv");
        file.get(0).write(tempFile);

        Map<String, Grid> grids = Grid.fromCsv(tempFile, latField, lonField, SeamlessCensusGridExtractor.ZOOM, (complete, total) -> {
            status.completedFeatures = complete;
            status.totalFeatures = total;
        });
        // clean up
        tempFile.delete();

        return grids;
    }

    /**
     * Create a grid from an input stream containing a binary grid file.
     * For those in the know, we can upload manually created binary grid files.
     */
    private static Map<String, Grid> createGridsFromBinaryGridFiles(Map<String, List<FileItem>> query, OpportunityDatasetUploadStatus status) throws Exception {
        Map<String, Grid> grids = new HashMap<>();
        List<FileItem> uploadedFiles = query.get("files");
        status.totalFeatures = uploadedFiles.size();
        for (FileItem fileItem : uploadedFiles) {
            Grid grid = Grid.read(fileItem.getInputStream());
            grids.put(fileItem.getName(), grid);
        }
        status.completedFeatures = status.totalFeatures;
        return grids;
    }

    private static Map<String, Grid> createGridsFromShapefile(Map<String, List<FileItem>> query, String baseName, OpportunityDatasetUploadStatus status) throws Exception {
        // extract relevant files: .shp, .prj, .dbf, and .shx.
        // We need the SHX even though we're looping over every feature as they might be sparse.
        Map<String, FileItem> filesByName = query.get("files").stream()
                .collect(Collectors.toMap(FileItem::getName, f -> f));

        if (!filesByName.containsKey(baseName + ".shp") ||
                !filesByName.containsKey(baseName + ".prj") ||
                !filesByName.containsKey(baseName + ".dbf")) {
            throw AnalysisServerException.fileUpload("Shapefile upload must contain .shp, .prj, and .dbf");
        }

        File tempDir = Files.createTempDir();

        File shpFile = new File(tempDir, "grid.shp");
        filesByName.get(baseName + ".shp").write(shpFile);

        File prjFile = new File(tempDir, "grid.prj");
        filesByName.get(baseName + ".prj").write(prjFile);

        File dbfFile = new File(tempDir, "grid.dbf");
        filesByName.get(baseName + ".dbf").write(dbfFile);

        // shx is optional, not needed for dense shapefiles
        if (filesByName.containsKey(baseName + ".shx")) {
            File shxFile = new File(tempDir, "grid.shx");
            filesByName.get(baseName + ".shx").write(shxFile);
        }

        Map<String, Grid> grids = Grid.fromShapefile(shpFile, SeamlessCensusGridExtractor.ZOOM, (complete, total) -> {
            status.completedFeatures = complete;
            status.totalFeatures = total;
        });

        tempDir.delete();
        return grids;
    }

    /**
     * Respond to a request with a redirect to a downloadable file.
     *
     * @req should specify regionId, opportunityDatasetId, and an available download format (.tiff or .grid)
     *
     */
    private static Object downloadOpportunityDataset (Request req, Response res) throws IOException {
        final String format = req.params("format");
        if (format.equals("grid")) return getOpportunityDataset(req, res);

        final OpportunityDataset opportunityDataset = Persistence.opportunityDatasets.findByIdFromRequestIfPermitted(req);
        final String bucketName = opportunityDataset.bucketName;

        String redirectText = req.queryParams("redirect");
        boolean redirect = redirectText == null || "".equals(redirectText) || parseBoolean(redirectText);

        if (!s3.doesObjectExist(bucketName, opportunityDataset.getKey(format))) {
            // if this grid is not on S3 in the requested format, try to get the .grid format
            if (!s3.doesObjectExist(bucketName, opportunityDataset.getKey("grid"))) {
                throw AnalysisServerException.notFound("This grid does not exist.");
            }

            // get the grid and convert it to the requested format
            S3Object s3Grid = s3.getObject(bucketName, opportunityDataset.getKey("grid"));
            InputStream rawInput = s3Grid.getObjectContent();
            Grid grid = Grid.read(new GZIPInputStream(rawInput));
            GridExporter.writeToS3(grid, s3, bucketName, opportunityDataset.getKey(format), format);
        }
        return GridExporter.downloadFromS3(s3, bucketName, opportunityDataset.getKey(format), redirect, res);
    }

    private static class OpportunityDatasetUploadStatus {
        public String id;
        public int totalFeatures = 0;
        public int completedFeatures = 0;
        public int totalGrids = 0;
        public int uploadedGrids = 0;
        public String regionId;
        public Status status = Status.PROCESSING;
        public String name;
        public String message;
        public Date createdAt;
        public Date completedAt;

        public OpportunityDatasetUploadStatus(String regionId, String name) {
            this.id = UUID.randomUUID().toString();
            this.regionId = regionId;
            this.name = name;
            this.createdAt = new Date();
        }

        public void completed () {
            this.completedAt = new Date();
        }
    }

    private enum Status {
        UPLOADING, PROCESSING, ERROR, DONE;
    }

    public static void register() {
        path("/api/opportunities", () -> {
            post("", OpportunityDatasetController::createOpportunityDataset, JsonUtil.objectMapper::writeValueAsString);
            get("/region/:regionId/status", OpportunityDatasetController::getRegionUploadStatuses, JsonUtil.objectMapper::writeValueAsString);
            delete("/region/:regionId/status/:statusId", OpportunityDatasetController::clearStatus, JsonUtil.objectMapper::writeValueAsString);
            get("/region/:regionId", OpportunityDatasetController::getRegionDatasets, JsonUtil.objectMapper::writeValueAsString);
            delete("/source/:sourceId", OpportunityDatasetController::deleteSourceSet, JsonUtil.objectMapper::writeValueAsString);
            delete("/:_id", OpportunityDatasetController::deleteOpportunityDataset, JsonUtil.objectMapper::writeValueAsString);
            get("/:_id", OpportunityDatasetController::getOpportunityDataset, JsonUtil.objectMapper::writeValueAsString);
            put("/:id", OpportunityDatasetController::editOpportunityDataset, JsonUtil.objectMapper::writeValueAsString);
            get("/:_id/:format", OpportunityDatasetController::downloadOpportunityDataset, JsonUtil.objectMapper::writeValueAsString);
        });
    }
}
