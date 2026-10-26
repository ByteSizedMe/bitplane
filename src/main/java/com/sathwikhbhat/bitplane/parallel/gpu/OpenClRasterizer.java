package com.sathwikhbhat.bitplane.parallel.gpu;

import static org.jocl.CL.*;

import com.sathwikhbhat.bitplane.constants.Constants;
import com.sathwikhbhat.bitplane.parallel.PayloadRasterizer;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds payload frame pixels with an OpenCL kernel (one work-item per pixel), several frames per launch.
 * PNG encoding stays on the CPU. Use {@link #create} which returns empty when no usable device exists.
 */
public final class OpenClRasterizer implements PayloadRasterizer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenClRasterizer.class);
    private static final String KERNEL_RESOURCE = "/kernels/payload_frames.cl";
    private static final int MAX_PAYLOAD = Constants.FRAME_BYTE_CAPACITY - Integer.BYTES;

    private final int batchFrames;
    private final String deviceDescription;
    private final boolean gpuDevice;
    private final cl_context context;
    private final cl_command_queue queue;
    private final cl_program program;
    private final cl_kernel kernel;
    private final cl_mem sourceBuffer;
    private final cl_mem outputBuffer;

    private OpenClRasterizer(cl_device_id device, String description, boolean gpuDevice, int batchFrames) {
        this.batchFrames = batchFrames;
        this.deviceDescription = description;
        this.gpuDevice = gpuDevice;

        cl_context_properties properties = new cl_context_properties();
        context = clCreateContext(properties, 1, new cl_device_id[] {device}, null, null, null);
        queue = clCreateCommandQueue(context, device, 0, null);
        program = clCreateProgramWithSource(context, 1, new String[] {loadKernelSource()}, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        kernel = clCreateKernel(program, "build_payload_frames", null);
        sourceBuffer = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) batchFrames * MAX_PAYLOAD, null, null);
        outputBuffer = clCreateBuffer(
                context, CL_MEM_WRITE_ONLY, (long) batchFrames * Constants.FRAME_BYTE_CAPACITY, null, null);
    }

    /**
     * @param allowCpuDevice also accept an OpenCL CPU device (e.g. pocl) when no GPU is present. Off in production;
     *     used by tests to exercise the kernel on machines without a GPU.
     * @return a ready rasterizer, or empty if OpenCL is missing or has no suitable device
     */
    public static Optional<OpenClRasterizer> create(boolean allowCpuDevice, int batchFrames) {
        try {
            CL.setExceptionsEnabled(true);
            Optional<DeviceChoice> choice = findDevice(CL_DEVICE_TYPE_GPU, true);
            if (choice.isEmpty() && allowCpuDevice) {
                choice = findDevice(CL_DEVICE_TYPE_ALL, false);
            }
            if (choice.isEmpty()) {
                log.info("No OpenCL {} device found", allowCpuDevice ? "" : "GPU");
                return Optional.empty();
            }
            DeviceChoice device = choice.get();
            return Optional.of(new OpenClRasterizer(
                    device.device(), device.description(), device.gpu(), Math.max(1, batchFrames)));
        } catch (Throwable t) {
            // UnsatisfiedLinkError (no libOpenCL), CLException (driver/build failure): caller falls back
            log.info("OpenCL unavailable: {}", t.toString());
            return Optional.empty();
        }
    }

    public String deviceDescription() {
        return deviceDescription;
    }

    public boolean isGpuDevice() {
        return gpuDevice;
    }

    @Override
    public synchronized List<BufferedImage> rasterize(byte[] fileBytes) {
        int totalFrames = (fileBytes.length + MAX_PAYLOAD - 1) / MAX_PAYLOAD;

        List<BufferedImage> images = new ArrayList<>(totalFrames);
        for (int i = 0; i < totalFrames; i++) {
            images.add(new BufferedImage(Constants.WIDTH, Constants.HEIGHT, BufferedImage.TYPE_BYTE_GRAY));
        }

        for (int first = 0; first < totalFrames; first += batchFrames) {
            int frames = Math.min(batchFrames, totalFrames - first);
            long sliceStart = (long) first * MAX_PAYLOAD;
            long sliceLength = Math.min((long) frames * MAX_PAYLOAD, fileBytes.length - sliceStart);

            clEnqueueWriteBuffer(
                    queue,
                    sourceBuffer,
                    CL_TRUE,
                    0,
                    sliceLength,
                    Pointer.to(fileBytes).withByteOffset(sliceStart),
                    0,
                    null,
                    null);

            clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(sourceBuffer));
            clSetKernelArg(kernel, 1, Sizeof.cl_long, Pointer.to(new long[] {sliceLength}));
            clSetKernelArg(kernel, 2, Sizeof.cl_int, Pointer.to(new int[] {first}));
            clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[] {MAX_PAYLOAD}));
            clSetKernelArg(kernel, 4, Sizeof.cl_mem, Pointer.to(outputBuffer));

            clEnqueueNDRangeKernel(
                    queue, kernel, 2, null, new long[] {Constants.FRAME_BYTE_CAPACITY, frames}, null, 0, null, null);

            for (int f = 0; f < frames; f++) {
                byte[] pixels =
                        ((DataBufferByte) images.get(first + f).getRaster().getDataBuffer()).getData();
                clEnqueueReadBuffer(
                        queue,
                        outputBuffer,
                        CL_TRUE,
                        (long) f * Constants.FRAME_BYTE_CAPACITY,
                        Constants.FRAME_BYTE_CAPACITY,
                        Pointer.to(pixels),
                        0,
                        null,
                        null);
            }
        }

        return images;
    }

    @Override
    public synchronized void close() {
        clReleaseMemObject(sourceBuffer);
        clReleaseMemObject(outputBuffer);
        clReleaseKernel(kernel);
        clReleaseProgram(program);
        clReleaseCommandQueue(queue);
        clReleaseContext(context);
    }

    @Override
    public String toString() {
        return "OpenClRasterizer[" + deviceDescription + ", batch=" + batchFrames + "]";
    }

    private record DeviceChoice(cl_device_id device, String description, boolean gpu) {}

    private static Optional<DeviceChoice> findDevice(long deviceType, boolean gpu) {
        int[] platformCount = new int[1];
        clGetPlatformIDs(0, null, platformCount);
        cl_platform_id[] platforms = new cl_platform_id[platformCount[0]];
        clGetPlatformIDs(platforms.length, platforms, null);

        for (cl_platform_id platform : platforms) {
            int[] deviceCount = new int[1];
            try {
                clGetDeviceIDs(platform, deviceType, 0, null, deviceCount);
            } catch (org.jocl.CLException e) {
                continue; // CL_DEVICE_NOT_FOUND on this platform
            }
            if (deviceCount[0] == 0) {
                continue;
            }
            cl_device_id[] devices = new cl_device_id[deviceCount[0]];
            clGetDeviceIDs(platform, deviceType, devices.length, devices, null);
            cl_device_id device = devices[0];
            long type = deviceLong(device, CL_DEVICE_TYPE);
            boolean isGpu = (type & CL_DEVICE_TYPE_GPU) != 0;
            String description = deviceString(device, CL_DEVICE_NAME)
                    + " (type=" + (isGpu ? "GPU" : "non-GPU")
                    + ", vendor=" + deviceString(device, CL_DEVICE_VENDOR)
                    + ", driver=" + deviceString(device, CL_DRIVER_VERSION) + ")";
            return Optional.of(new DeviceChoice(device, description, gpu && isGpu));
        }
        return Optional.empty();
    }

    private static String deviceString(cl_device_id device, int param) {
        long[] size = new long[1];
        clGetDeviceInfo(device, param, 0, null, size);
        byte[] buffer = new byte[(int) size[0]];
        clGetDeviceInfo(device, param, buffer.length, Pointer.to(buffer), null);
        return new String(buffer, 0, buffer.length - 1, StandardCharsets.UTF_8).trim();
    }

    private static long deviceLong(cl_device_id device, int param) {
        long[] value = new long[1];
        clGetDeviceInfo(device, param, Sizeof.cl_long, Pointer.to(value), null);
        return value[0];
    }

    private static String loadKernelSource() {
        try (InputStream in = OpenClRasterizer.class.getResourceAsStream(KERNEL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing kernel resource " + KERNEL_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read kernel resource " + KERNEL_RESOURCE, e);
        }
    }
}
