package com.salkcoding.oswl.local;

import com.salkcoding.oswl.util.LogoImageNormalizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Throwaway sanity check for {@link LogoImageNormalizer}. Not part of the build or test
 * suite — compiled and run manually, then deleted.
 */
public final class LogoResizeSanityCheck {

    private LogoResizeSanityCheck() {}

    static void main(String[] args) throws Exception {
        int failures = 0;

        // 1. Oversized PNG with alpha → downscaled to 256 on the long edge, alpha kept
        String bigPng = toDataUri(makeImage(2000, 1000, true), "png");
        String resized = LogoImageNormalizer.normalize(bigPng);
        BufferedImage decoded = decode(resized);
        failures += check("oversized PNG downscaled to 256x128",
                decoded.getWidth() == 256 && decoded.getHeight() == 128);
        failures += check("resized PNG keeps alpha",
                resized.startsWith("data:image/png;base64,") && decoded.getColorModel().hasAlpha());

        // 2. Small image → returned byte-identical (no re-encode)
        String small = toDataUri(makeImage(100, 60, true), "png");
        failures += check("small image returned unchanged", small.equals(LogoImageNormalizer.normalize(small)));

        // 3. Oversized JPEG → stays JPEG, no alpha
        String bigJpeg = toDataUri(makeImage(1024, 2048, false), "jpeg");
        String resizedJpeg = LogoImageNormalizer.normalize(bigJpeg);
        BufferedImage decodedJpeg = decode(resizedJpeg);
        failures += check("oversized JPEG stays jpeg, 128x256",
                resizedJpeg.startsWith("data:image/jpeg;base64,")
                        && decodedJpeg.getWidth() == 128 && decodedJpeg.getHeight() == 256);
        failures += check("resized JPEG has no alpha", !decodedJpeg.getColorModel().hasAlpha());

        // 4. Exact-boundary image (256px) → unchanged
        String boundary = toDataUri(makeImage(256, 100, false), "png");
        failures += check("256px boundary image unchanged", boundary.equals(LogoImageNormalizer.normalize(boundary)));

        // 5. Garbage → IllegalArgumentException
        failures += check("non-image data URI rejected",
                throwsIllegal(() -> LogoImageNormalizer.normalize("data:image/png;base64,aGVsbG8gd29ybGQ=")));
        failures += check("non-base64 payload rejected",
                throwsIllegal(() -> LogoImageNormalizer.normalize("data:image/png;base64,!!!notbase64!!!")));
        failures += check("plain text rejected",
                throwsIllegal(() -> LogoImageNormalizer.normalize("hello")));

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static BufferedImage makeImage(int w, int h, boolean alpha) {
        return new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }

    private static String toDataUri(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return "data:image/" + format + ";base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static BufferedImage decode(String dataUri) throws Exception {
        String payload = dataUri.substring(dataUri.indexOf(',') + 1);
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(payload)));
    }

    private static boolean throwsIllegal(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + label);
        return ok ? 0 : 1;
    }
}
