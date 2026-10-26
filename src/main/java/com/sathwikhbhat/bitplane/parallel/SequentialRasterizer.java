package com.sathwikhbhat.bitplane.parallel;

import com.sathwikhbhat.bitplane.frame.builder.PayloadFrameBuilder;
import com.sathwikhbhat.bitplane.frame.model.PayloadFrame;
import com.sathwikhbhat.bitplane.image.ImageGenerator;
import java.awt.image.BufferedImage;
import java.util.List;

/** Reference implementation: exactly what {@code EncodePipeline} does. */
public class SequentialRasterizer implements PayloadRasterizer {

    private final PayloadFrameBuilder payloadFrameBuilder = new PayloadFrameBuilder();
    private final ImageGenerator imageGenerator = new ImageGenerator();

    @Override
    public List<BufferedImage> rasterize(byte[] fileBytes) {
        List<PayloadFrame> payloadFrames = payloadFrameBuilder.build(fileBytes);
        return payloadFrames.stream().map(imageGenerator::payloadToImage).toList();
    }
}
