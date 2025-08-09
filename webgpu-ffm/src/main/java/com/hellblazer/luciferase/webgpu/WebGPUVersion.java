package com.hellblazer.luciferase.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * WebGPU native library version information.
 * Reads version properties from the packaged resources.
 */
public class WebGPUVersion {
    private static final Logger log = LoggerFactory.getLogger(WebGPUVersion.class);
    private static final String VERSION_RESOURCE = "/META-INF/webgpu-version.properties";
    
    private static final Properties versionProperties = new Properties();
    private static boolean loaded = false;
    
    static {
        loadVersionProperties();
    }
    
    /**
     * Get the wgpu-native library version.
     * 
     * @return the version string, or "unknown" if not available
     */
    public static String getWgpuVersion() {
        return versionProperties.getProperty("wgpu.version", "unknown");
    }
    
    /**
     * Get the release date of the wgpu-native library.
     * 
     * @return the release date string, or "unknown" if not available
     */
    public static String getReleaseDate() {
        return versionProperties.getProperty("wgpu.release.date", "unknown");
    }
    
    /**
     * Get the date when the native libraries were downloaded.
     * 
     * @return the download date string, or "unknown" if not available
     */
    public static String getDownloadDate() {
        return versionProperties.getProperty("wgpu.download.date", "unknown");
    }
    
    /**
     * Get all version information as a formatted string.
     * 
     * @return formatted version information
     */
    public static String getVersionInfo() {
        var sb = new StringBuilder();
        sb.append("WebGPU Native Library Version Information:\n");
        sb.append("  wgpu-native version: ").append(getWgpuVersion()).append("\n");
        sb.append("  Release date: ").append(getReleaseDate()).append("\n");
        sb.append("  Downloaded: ").append(getDownloadDate());
        return sb.toString();
    }
    
    /**
     * Check if version information is available.
     * 
     * @return true if version properties were successfully loaded
     */
    public static boolean isVersionInfoAvailable() {
        return loaded && !versionProperties.isEmpty();
    }
    
    private static void loadVersionProperties() {
        try (InputStream is = WebGPUVersion.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (is != null) {
                versionProperties.load(is);
                loaded = true;
                log.debug("Loaded WebGPU version properties");
            } else {
                log.warn("WebGPU version properties not found at: {}", VERSION_RESOURCE);
            }
        } catch (IOException e) {
            log.error("Failed to load WebGPU version properties", e);
        }
    }
}