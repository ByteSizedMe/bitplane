package com.sathwikhbhat.bitplane.pipeline;

import com.sathwikhbhat.bitplane.frame.model.MetadataFrame;
import com.sathwikhbhat.bitplane.image.ImageGenerator;
import com.sathwikhbhat.bitplane.parallel.PayloadRasterizer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link EncodePipeline} with a pluggable (threaded or GPU) payload rasterizer. Produces identical images. */
public class ParallelEncodePipeline {

    private static final Logger log = LoggerFactory.getLogger(ParallelEncodePipeline.class);

    private final PayloadRasterizer rasterizer;
    private final ImageGenerator imageGenerator = new ImageGenerator();

    public ParallelEncodePipeline(PayloadRasterizer rasterizer) {
        this.rasterizer = rasterizer;
    }

    public ImageFrameSet encode(Path inputFile, String originalFileName) throws IOException {
        byte[] fileBytes = Files.readAllBytes(inputFile);

        log.debug("Encoding file: {} ({} bytes) with {}", originalFileName, fileBytes.length, rasterizer);

        if (fileBytes.length == 0) {
            throw new IllegalArgumentException("File is empty");
        }

        List<BufferedImage> payloadFrameImages = rasterizer.rasterize(fileBytes);
        MetadataFrame metadataFrame = new MetadataFrame(originalFileName, fileBytes.length, payloadFrameImages.size());
        BufferedImage metadataFrameImage = imageGenerator.metadataToImage(metadataFrame);

        return new ImageFrameSet(metadataFrameImage, payloadFrameImages);
    }
}
