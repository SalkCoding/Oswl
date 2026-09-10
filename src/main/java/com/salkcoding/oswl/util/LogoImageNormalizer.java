package com.salkcoding.oswl.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and downscales user-supplied logo images (stored as base64 data URIs).
 * The logos are only ever rendered at a few dozen CSS pixels (report header/cover, mail
 * templates), so anything beyond {@link #MAX_DIMENSION} on the long edge is wasted bytes
 * in every report page and mail — the image is decoded, downscaled, and re-encoded at
 * save time instead of shipping the original multi-megabyte upload to every render.
 */
public final class LogoImageNormalizer {

    /** Long-edge cap in pixels — ~3x headroom over the largest render size (76px cover logo). */
    public static final int MAX_DIMENSION = 256;

    private static final Pattern DATA_URI =
            Pattern.compile("^data:image/([a-z0-9.+-]+);base64,(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private LogoImageNormalizer() {}

    /**
     * Returns the data URI to persist: the input unchanged when it already fits, otherwise a
     * downscaled re-encode (PNG when the image has alpha or the source format isn't
     * re-encodable, JPEG otherwise). Animated formats keep only their first frame once
     * resized; images small enough to keep are returned byte-identical (animation and
     * metadata untouched).
     *
     * @throws IllegalArgumentException when the string is not a decodable image data URI
     */
    public static String normalize(String dataUri) {
        Matcher m = DATA_URI.matcher(dataUri.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Logo must be a base64 image data URI");
        }
        String subtype = m.group(1).toLowerCase(Locale.ROOT);
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Logo image data URI is not valid base64", e);
        }
        final BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Logo image could not be decoded", e);
        }
        if (image == null) {
            throw new IllegalArgumentException("Logo image could not be decoded");
        }

        int w = image.getWidth();
        int h = image.getHeight();
        if (Math.max(w, h) <= MAX_DIMENSION) {
            return dataUri;
        }

        double scale = (double) MAX_DIMENSION / Math.max(w, h);
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));

        boolean keepAlpha = image.getColorModel().hasAlpha() || "png".equals(subtype) || "gif".equals(subtype);
        BufferedImage scaled = new BufferedImage(newW, newH,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }

        String format = keepAlpha ? "png" : (ImageIO.getImageWritersByFormatName(subtype).hasNext() ? subtype : "png");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(scaled, format, out)) {
                // No writer for the source format — fall back to PNG (always available).
                format = "png";
                out.reset();
                ImageIO.write(scaled, format, out);
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Logo image could not be re-encoded", e);
        }
        return "data:image/" + format + ";base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
