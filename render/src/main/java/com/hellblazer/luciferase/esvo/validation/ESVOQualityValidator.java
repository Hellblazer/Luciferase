package com.hellblazer.luciferase.esvo.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Quality validator for ESVO rendering results providing comprehensive image quality metrics.
 * 
 * This validator implements industry-standard quality assessment metrics including:
 * - PSNR (Peak Signal-to-Noise Ratio) for overall image quality
 * - SSIM (Structural Similarity Index) for structural similarity assessment
 * - MSE (Mean Squared Error) for pixel-level error measurement
 * - Perceptual error metrics for visual quality assessment
 * 
 * Quality thresholds are based on standard computer vision benchmarks for real-time rendering.
 */
public class ESVOQualityValidator {
    private static final Logger log = LoggerFactory.getLogger(ESVOQualityValidator.class);

    // Quality thresholds based on standard computer vision benchmarks
    public static final double PSNR_THRESHOLD = 30.0; // dB - typical threshold for acceptable image quality
    public static final double SSIM_THRESHOLD = 0.85; // Structural similarity threshold (0-1 scale)
    public static final double MSE_THRESHOLD = 100.0; // Mean squared error threshold
    public static final double PERCEPTUAL_ERROR_THRESHOLD = 0.15; // Perceptual error threshold (0-1 scale)

    // SSIM computation constants
    private static final double SSIM_K1 = 0.01;
    private static final double SSIM_K2 = 0.03;
    private static final int SSIM_DYNAMIC_RANGE = 255; // For 8-bit images
    private static final double SSIM_C1 = (SSIM_K1 * SSIM_DYNAMIC_RANGE) * (SSIM_K1 * SSIM_DYNAMIC_RANGE);
    private static final double SSIM_C2 = (SSIM_K2 * SSIM_DYNAMIC_RANGE) * (SSIM_K2 * SSIM_DYNAMIC_RANGE);

    /**
     * Validates quality between rendered and reference images.
     * 
     * @param rendered The rendered image result from ESVO
     * @param reference The reference image for comparison
     * @return QualityReport containing all quality metrics
     * @throws IllegalArgumentException if inputs are null or dimensions don't match
     */
    public QualityReport validateQuality(ESVORenderResult rendered, ESVORenderResult reference) {
        Objects.requireNonNull(rendered, "Rendered result cannot be null");
        Objects.requireNonNull(reference, "Reference result cannot be null");

        if (rendered.getWidth() != reference.getWidth() || rendered.getHeight() != reference.getHeight()) {
            throw new IllegalArgumentException("Image dimensions must match: rendered=" + 
                rendered.getWidth() + "x" + rendered.getHeight() + 
                ", reference=" + reference.getWidth() + "x" + reference.getHeight());
        }

        var renderedBuffer = rendered.getImageData();
        var referenceBuffer = reference.getImageData();
        var width = rendered.getWidth();
        var height = rendered.getHeight();

        log.debug("Validating quality for {}x{} images", width, height);

        var psnr = calculatePSNR(renderedBuffer, referenceBuffer, width, height);
        var ssim = calculateSSIM(renderedBuffer, referenceBuffer, width, height);
        var mse = calculateMSE(renderedBuffer, referenceBuffer, width, height);
        var perceptualError = calculatePerceptualError(renderedBuffer, referenceBuffer, width, height);

        var report = new QualityReport(psnr, ssim, mse, perceptualError);
        
        log.info("Quality validation complete: PSNR={:.2f}dB, SSIM={:.3f}, MSE={:.2f}, PE={:.3f}", 
                 psnr, ssim, mse, perceptualError);

        return report;
    }

    /**
     * Calculates Peak Signal-to-Noise Ratio (PSNR) between two images.
     * PSNR is a measure of image quality based on the ratio between maximum possible
     * power of the signal and the power of corrupting noise.
     * 
     * @param rendered The rendered image buffer (RGBA format)
     * @param reference The reference image buffer (RGBA format)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return PSNR value in decibels (higher is better, typically 20-50 dB)
     */
    public double calculatePSNR(ByteBuffer rendered, ByteBuffer reference, int width, int height) {
        Objects.requireNonNull(rendered, "Rendered buffer cannot be null");
        Objects.requireNonNull(reference, "Reference buffer cannot be null");

        var mse = calculateMSE(rendered, reference, width, height);
        
        if (mse == 0.0) {
            log.debug("Images are identical, PSNR is infinite");
            return Double.POSITIVE_INFINITY;
        }

        var maxPixelValue = 255.0; // For 8-bit images
        var psnr = 20.0 * Math.log10(maxPixelValue / Math.sqrt(mse));
        
        log.debug("PSNR calculated: {:.2f} dB (MSE: {:.2f})", psnr, mse);
        return psnr;
    }

    /**
     * Calculates Structural Similarity Index Measure (SSIM) between two images.
     * SSIM assesses image quality based on structural similarity rather than pixel differences.
     * 
     * @param rendered The rendered image buffer (RGBA format)
     * @param reference The reference image buffer (RGBA format)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return SSIM value between 0 and 1 (1 indicates perfect structural similarity)
     */
    public double calculateSSIM(ByteBuffer rendered, ByteBuffer reference, int width, int height) {
        Objects.requireNonNull(rendered, "Rendered buffer cannot be null");
        Objects.requireNonNull(reference, "Reference buffer cannot be null");

        // Convert to grayscale for SSIM computation
        var renderedGray = convertToGrayscale(rendered, width, height);
        var referenceGray = convertToGrayscale(reference, width, height);

        // Calculate means
        var meanX = calculateMean(renderedGray);
        var meanY = calculateMean(referenceGray);

        // Calculate variances and covariance
        var varianceX = calculateVariance(renderedGray, meanX);
        var varianceY = calculateVariance(referenceGray, meanY);
        var covariance = calculateCovariance(renderedGray, referenceGray, meanX, meanY);

        // SSIM formula
        var numerator = (2 * meanX * meanY + SSIM_C1) * (2 * covariance + SSIM_C2);
        var denominator = (meanX * meanX + meanY * meanY + SSIM_C1) * (varianceX + varianceY + SSIM_C2);

        var ssim = numerator / denominator;
        
        log.debug("SSIM calculated: {:.3f} (means: {:.2f}, {:.2f})", ssim, meanX, meanY);
        return Math.max(0.0, Math.min(1.0, ssim)); // Clamp to [0,1]
    }

    /**
     * Calculates Mean Squared Error (MSE) between two images.
     * MSE measures the average squared difference between corresponding pixels.
     * 
     * @param rendered The rendered image buffer (RGBA format)
     * @param reference The reference image buffer (RGBA format)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return MSE value (lower is better, 0 indicates identical images)
     */
    public double calculateMSE(ByteBuffer rendered, ByteBuffer reference, int width, int height) {
        Objects.requireNonNull(rendered, "Rendered buffer cannot be null");
        Objects.requireNonNull(reference, "Reference buffer cannot be null");

        var totalPixels = width * height;
        var bytesPerPixel = 4; // RGBA format
        var mse = 0.0;

        rendered.rewind();
        reference.rewind();

        for (var i = 0; i < totalPixels * bytesPerPixel; i++) {
            var renderedValue = rendered.get(i) & 0xFF; // Convert to unsigned
            var referenceValue = reference.get(i) & 0xFF;
            var diff = renderedValue - referenceValue;
            mse += diff * diff;
        }

        mse /= (totalPixels * bytesPerPixel);
        
        log.debug("MSE calculated: {:.2f} over {} pixels", mse, totalPixels);
        return mse;
    }

    /**
     * Calculates perceptual error metric based on human visual system characteristics.
     * This metric weights errors by their perceptual importance.
     * 
     * @param rendered The rendered image buffer (RGBA format)
     * @param reference The reference image buffer (RGBA format)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return Perceptual error value between 0 and 1 (lower is better)
     */
    public double calculatePerceptualError(ByteBuffer rendered, ByteBuffer reference, int width, int height) {
        Objects.requireNonNull(rendered, "Rendered buffer cannot be null");
        Objects.requireNonNull(reference, "Reference buffer cannot be null");

        var totalPixels = width * height;
        var perceptualError = 0.0;

        rendered.rewind();
        reference.rewind();

        // Perceptual weights for RGB channels (based on human visual sensitivity)
        var redWeight = 0.299;
        var greenWeight = 0.587;
        var blueWeight = 0.114;

        for (var i = 0; i < totalPixels; i++) {
            var pixelOffset = i * 4; // RGBA format

            // Extract RGB values
            var renderedR = rendered.get(pixelOffset) & 0xFF;
            var renderedG = rendered.get(pixelOffset + 1) & 0xFF;
            var renderedB = rendered.get(pixelOffset + 2) & 0xFF;

            var referenceR = reference.get(pixelOffset) & 0xFF;
            var referenceG = reference.get(pixelOffset + 1) & 0xFF;
            var referenceB = reference.get(pixelOffset + 2) & 0xFF;

            // Calculate weighted perceptual difference
            var diffR = Math.abs(renderedR - referenceR) / 255.0;
            var diffG = Math.abs(renderedG - referenceG) / 255.0;
            var diffB = Math.abs(renderedB - referenceB) / 255.0;

            var perceptualDiff = redWeight * diffR + greenWeight * diffG + blueWeight * diffB;
            perceptualError += perceptualDiff * perceptualDiff; // Square for emphasis
        }

        perceptualError = Math.sqrt(perceptualError / totalPixels);
        
        log.debug("Perceptual error calculated: {:.3f}", perceptualError);
        return perceptualError;
    }

    /**
     * Generates a comprehensive quality report string.
     * 
     * @param report The quality report to format
     * @return Formatted quality report string
     */
    public String generateQualityReport(QualityReport report) {
        Objects.requireNonNull(report, "Quality report cannot be null");

        var sb = new StringBuilder();
        sb.append("ESVO Quality Assessment Report\n");
        sb.append("================================\n\n");

        // PSNR assessment
        sb.append(String.format("Peak Signal-to-Noise Ratio (PSNR): %.2f dB\n", report.psnr()));
        sb.append(String.format("  Threshold: %.1f dB - %s\n", 
                  PSNR_THRESHOLD, 
                  report.psnr() >= PSNR_THRESHOLD ? "PASS" : "FAIL"));

        // SSIM assessment
        sb.append(String.format("Structural Similarity Index (SSIM): %.3f\n", report.ssim()));
        sb.append(String.format("  Threshold: %.2f - %s\n", 
                  SSIM_THRESHOLD, 
                  report.ssim() >= SSIM_THRESHOLD ? "PASS" : "FAIL"));

        // MSE assessment
        sb.append(String.format("Mean Squared Error (MSE): %.2f\n", report.mse()));
        sb.append(String.format("  Threshold: %.1f - %s\n", 
                  MSE_THRESHOLD, 
                  report.mse() <= MSE_THRESHOLD ? "PASS" : "FAIL"));

        // Perceptual error assessment
        sb.append(String.format("Perceptual Error: %.3f\n", report.perceptualError()));
        sb.append(String.format("  Threshold: %.2f - %s\n", 
                  PERCEPTUAL_ERROR_THRESHOLD, 
                  report.perceptualError() <= PERCEPTUAL_ERROR_THRESHOLD ? "PASS" : "FAIL"));

        // Overall assessment
        sb.append("\nOverall Quality Assessment: ");
        if (report.passesAllThresholds()) {
            sb.append("EXCELLENT - All metrics within acceptable thresholds\n");
        } else {
            sb.append("NEEDS IMPROVEMENT - Some metrics below thresholds\n");
        }

        return sb.toString();
    }

    // Helper methods for SSIM computation

    private double[] convertToGrayscale(ByteBuffer rgbaBuffer, int width, int height) {
        var totalPixels = width * height;
        var grayscale = new double[totalPixels];
        
        rgbaBuffer.rewind();
        
        for (var i = 0; i < totalPixels; i++) {
            var pixelOffset = i * 4; // RGBA format
            var r = rgbaBuffer.get(pixelOffset) & 0xFF;
            var g = rgbaBuffer.get(pixelOffset + 1) & 0xFF;
            var b = rgbaBuffer.get(pixelOffset + 2) & 0xFF;
            
            // Standard luminance conversion
            grayscale[i] = 0.299 * r + 0.587 * g + 0.114 * b;
        }
        
        return grayscale;
    }

    private double calculateMean(double[] values) {
        var sum = 0.0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private double calculateVariance(double[] values, double mean) {
        var variance = 0.0;
        for (var value : values) {
            var diff = value - mean;
            variance += diff * diff;
        }
        return variance / values.length;
    }

    private double calculateCovariance(double[] valuesX, double[] valuesY, double meanX, double meanY) {
        if (valuesX.length != valuesY.length) {
            throw new IllegalArgumentException("Arrays must have the same length");
        }
        
        var covariance = 0.0;
        for (var i = 0; i < valuesX.length; i++) {
            covariance += (valuesX[i] - meanX) * (valuesY[i] - meanY);
        }
        return covariance / valuesX.length;
    }

    /**
     * Quality report containing all computed metrics and threshold validation.
     */
    public record QualityReport(
        double psnr,
        double ssim,
        double mse,
        double perceptualError
    ) {
        /**
         * Checks if all quality metrics pass their respective thresholds.
         * 
         * @return true if all metrics are within acceptable ranges
         */
        public boolean passesAllThresholds() {
            return psnr >= PSNR_THRESHOLD &&
                   ssim >= SSIM_THRESHOLD &&
                   mse <= MSE_THRESHOLD &&
                   perceptualError <= PERCEPTUAL_ERROR_THRESHOLD;
        }

        /**
         * Gets the overall quality score as a weighted combination of all metrics.
         * 
         * @return Quality score between 0 and 1 (higher is better)
         */
        public double getOverallQualityScore() {
            // Normalize metrics to [0,1] scale
            var normalizedPsnr = Math.min(1.0, Math.max(0.0, psnr / 50.0)); // PSNR typically 0-50 dB
            var normalizedSsim = ssim; // Already 0-1
            var normalizedMse = Math.min(1.0, Math.max(0.0, 1.0 - (mse / 255.0))); // Invert MSE
            var normalizedPe = Math.min(1.0, Math.max(0.0, 1.0 - perceptualError)); // Invert PE

            // Weighted combination (SSIM and perceptual error get higher weights)
            return 0.2 * normalizedPsnr + 0.3 * normalizedSsim + 0.2 * normalizedMse + 0.3 * normalizedPe;
        }
    }

    /**
     * Placeholder interface for ESVO render results.
     * This should be replaced with the actual ESVORenderResult class when available.
     */
    public interface ESVORenderResult {
        ByteBuffer getImageData();
        int getWidth();
        int getHeight();
    }
}