package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Kitty terminal graphics protocol, v1 subset. See `scope.md` in the repo root for what is in
 * and out of scope.
 * - https://sw.kovidgoyal.net/kitty/graphics-protocol/
 *
 * Commands arrive as `APC G <control data> ; <payload> ST` and are passed here from
 * {@link TerminalEmulator} with the leading `G` already stripped. Decoded images are stamped into
 * the screen through {@link TerminalBuffer#addTerminalBitmapForKitty}, so scrolling, garbage
 * collection and drawing are shared with the sixel and iTerm image support.
 *
 * Supported: actions `t`, `T`, `p`, `q`, `d`; formats 24, 32, 100 (PNG); compression `o=z`;
 * chunked transfer `m=1`; media `t=d`, `t=f`, `t=t`; keys `i`, `I`, `p`, `x`, `y`, `w`, `h`,
 * `X`, `Y`, `c`, `r`, `C`, `q`, `s`, `v`, `S`, `O`.
 * Not yet: animation frames, negative z-index (behind text), shared memory `t=s`, Unicode
 * placeholders `U=1`.
 */
public class TerminalKitty {

    private static final String LOG_TAG = "TerminalKitty";

    /** Total decoded bytes kept for stored images before the oldest ones are evicted. */
    private static final long STORAGE_QUOTA_BYTES = 128L * 1024 * 1024;

    private static final int FORMAT_RGB = 24;
    private static final int FORMAT_RGBA = 32;
    private static final int FORMAT_PNG = 100;

    private final TerminalEmulator mEmulator;

    /** Stored images by image id, in insertion order for eviction. */
    private final LinkedHashMap<Integer, Bitmap> mImages = new LinkedHashMap<>();
    private long mImagesBytes;
    /** Image number (`I=`) to the newest image id that was assigned for it. */
    private final HashMap<Integer, Integer> mImageNumbers = new HashMap<>();
    private int mNextAutoImageId = 0x7f000000;

    /** A displayed placement of a stored image (or of an unstored one, with image id 0). */
    private static class Placement {
        final int imageId;
        final int placementId;
        final TerminalBitmap terminalBitmap;

        Placement(int imageId, int placementId, TerminalBitmap terminalBitmap) {
            this.imageId = imageId;
            this.placementId = placementId;
            this.terminalBitmap = terminalBitmap;
        }
    }
    private final List<Placement> mPlacements = new ArrayList<>();

    /** Control keys of the first chunk of a chunked (`m=1`) transmission in progress. */
    private Map<Character, String> mChunkedControl;
    private ByteArrayOutputStream mChunkedData;

    public TerminalKitty(TerminalEmulator emulator) {
        mEmulator = emulator;
    }

    /** Thrown for protocol errors, reported to the client as `CODE:message`. */
    private static class KittyException extends Exception {
        final String code;

        KittyException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Handle one `APC G ... ST` command, given everything after the `G`. */
    public void handleCommand(CharSequence command) {
        int separator = -1;
        for (int i = 0; i < command.length(); i++) {
            if (command.charAt(i) == ';') {
                separator = i;
                break;
            }
        }
        String controlString = (separator < 0 ? command : command.subSequence(0, separator)).toString();
        String payload = separator < 0 ? "" : command.subSequence(separator + 1, command.length()).toString();

        Map<Character, String> control = parseControl(controlString);

        if (mChunkedControl != null) {
            // Later chunks only carry `m` (and optionally `q`); everything else comes from the first chunk.
            continueChunked(control, payload);
            return;
        }

        try {
            if (getInt(control, 'm', 0) == 1) {
                char action = getChar(control, 'a', 't');
                if (action != 't' && action != 'T' && action != 'q') {
                    throw new KittyException("EINVAL", "chunking is only supported for transmit and query");
                }
                mChunkedControl = control;
                mChunkedData = new ByteArrayOutputStream();
                appendBase64(mChunkedData, payload);
                return;
            }
            byte[] data = decodeBase64(payload);
            handle(control, data);
        } catch (KittyException e) {
            respond(control, e.code + ":" + e.getMessage());
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(mEmulator.getClientForKitty(), LOG_TAG, "Kitty graphics command failed: " + t.getMessage());
            respond(control, "EINVAL:" + t.getClass().getSimpleName());
        }
    }

    private void continueChunked(Map<Character, String> chunkControl, String payload) {
        Map<Character, String> control = mChunkedControl;
        try {
            appendBase64(mChunkedData, payload);
            if (getInt(chunkControl, 'm', 0) == 1) return;
            if (chunkControl.containsKey('q')) control.put('q', chunkControl.get('q'));
            byte[] data = mChunkedData.toByteArray();
            mChunkedControl = null;
            mChunkedData = null;
            handle(control, data);
        } catch (KittyException e) {
            mChunkedControl = null;
            mChunkedData = null;
            respond(control, e.code + ":" + e.getMessage());
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            mChunkedControl = null;
            mChunkedData = null;
            Logger.logError(mEmulator.getClientForKitty(), LOG_TAG, "Kitty graphics chunked command failed: " + t.getMessage());
            respond(control, "EINVAL:" + t.getClass().getSimpleName());
        }
    }

    private void handle(Map<Character, String> control, byte[] payload) throws KittyException {
        char action = getChar(control, 'a', 't');
        switch (action) {
            case 't':
            case 'T':
            case 'q': {
                Bitmap bitmap = loadImage(control, payload);
                if (action == 'q') {
                    respond(control, "OK");
                    return;
                }
                int imageId = assignImageId(control);
                if (imageId != 0) storeImage(imageId, bitmap);
                if (action == 'T') place(control, imageId, bitmap);
                respond(control, "OK");
                return;
            }
            case 'p': {
                int imageId = resolveImageId(control);
                Bitmap bitmap = mImages.get(imageId);
                if (bitmap == null) throw new KittyException("ENOENT", "no image with id " + imageId);
                place(control, imageId, bitmap);
                respond(control, "OK");
                return;
            }
            case 'd':
                delete(control);
                return;
            default:
                throw new KittyException("EINVAL", "unsupported action " + action);
        }
    }



    /* ---------- loading ---------- */

    private Bitmap loadImage(Map<Character, String> control, byte[] payload) throws KittyException {
        char medium = getChar(control, 't', 'd');
        byte[] data;
        switch (medium) {
            case 'd':
                data = payload;
                break;
            case 'f':
            case 't':
                data = readFile(new String(payload, java.nio.charset.StandardCharsets.UTF_8), medium == 't',
                    getInt(control, 'O', 0), getInt(control, 'S', 0));
                break;
            case 's':
                throw new KittyException("EINVAL", "shared memory transfer not supported");
            default:
                throw new KittyException("EINVAL", "unknown transmission medium " + medium);
        }

        if (getChar(control, 'o', '\0') == 'z') data = inflate(data);

        int format = getInt(control, 'f', FORMAT_RGBA);
        switch (format) {
            case FORMAT_PNG: {
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) throw new KittyException("EBADPNG", "failed to decode image");
                checkSize(bitmap.getWidth(), bitmap.getHeight());
                return bitmap;
            }
            case FORMAT_RGB:
            case FORMAT_RGBA: {
                int width = getInt(control, 's', 0);
                int height = getInt(control, 'v', 0);
                if (width <= 0 || height <= 0) throw new KittyException("EINVAL", "width and height required");
                checkSize(width, height);
                int bytesPerPixel = format == FORMAT_RGB ? 3 : 4;
                if (data.length < (long) width * height * bytesPerPixel) {
                    throw new KittyException("ENODATA", "insufficient image data");
                }
                int[] pixels = new int[width * height];
                for (int i = 0, j = 0; i < pixels.length; i++, j += bytesPerPixel) {
                    int r = data[j] & 0xff, g = data[j + 1] & 0xff, b = data[j + 2] & 0xff;
                    int a = bytesPerPixel == 4 ? data[j + 3] & 0xff : 0xff;
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            }
            default:
                throw new KittyException("EINVAL", "unknown format " + format);
        }
    }

    private static void checkSize(int width, int height) throws KittyException {
        if ((long) width * height * 4 > TerminalBitmap.MAX_BITMAP_SIZE) {
            throw new KittyException("EFBIG", "image too large");
        }
    }

    private static byte[] readFile(String path, boolean isTemp, int offset, int size) throws KittyException {
        File file = new File(path);
        String canonical;
        try {
            canonical = file.getCanonicalPath();
        } catch (Exception e) {
            throw new KittyException("EBADF", "invalid path");
        }
        if (canonical.startsWith("/proc/") || canonical.startsWith("/sys/") || canonical.startsWith("/dev/")) {
            throw new KittyException("EBADF", "refusing to read device files");
        }
        if (isTemp && !(canonical.contains("tty-graphics-protocol") && canonical.contains("/tmp/"))) {
            throw new KittyException("EBADF", "temporary file not in a temp dir or missing tty-graphics-protocol in name");
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (offset < 0 || offset > length) throw new KittyException("EINVAL", "bad offset");
            long toRead = size > 0 ? Math.min(size, length - offset) : length - offset;
            if (toRead > TerminalBitmap.MAX_BITMAP_SIZE) throw new KittyException("EFBIG", "file too large");
            byte[] data = new byte[(int) toRead];
            raf.seek(offset);
            raf.readFully(data);
            return data;
        } catch (KittyException e) {
            throw e;
        } catch (Exception e) {
            throw new KittyException("EBADF", "failed to read file");
        } finally {
            if (isTemp) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
    }

    private static byte[] inflate(byte[] data) throws KittyException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] buffer = new byte[64 * 1024];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new KittyException("EINVAL", "truncated zlib data");
                }
                out.write(buffer, 0, n);
                if (out.size() > TerminalBitmap.MAX_BITMAP_SIZE) throw new KittyException("EFBIG", "image too large");
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new KittyException("EINVAL", "bad zlib data");
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }



    /* ---------- storage ---------- */

    private int assignImageId(Map<Character, String> control) throws KittyException {
        int imageId = getInt(control, 'i', 0);
        int imageNumber = getInt(control, 'I', 0);
        if (imageId != 0 && imageNumber != 0) throw new KittyException("EINVAL", "both i and I given");
        if (imageNumber != 0) {
            do {
                imageId = mNextAutoImageId++;
                if (mNextAutoImageId <= 0) mNextAutoImageId = 0x7f000000;
            } while (mImages.containsKey(imageId));
            mImageNumbers.put(imageNumber, imageId);
            // Responses for `I=` carry the assigned id.
            control.put('i', Integer.toString(imageId));
        }
        return imageId;
    }

    private int resolveImageId(Map<Character, String> control) throws KittyException {
        int imageId = getInt(control, 'i', 0);
        if (imageId != 0) return imageId;
        int imageNumber = getInt(control, 'I', 0);
        Integer resolved = imageNumber != 0 ? mImageNumbers.get(imageNumber) : null;
        if (resolved == null) throw new KittyException("ENOENT", "no image id or number");
        control.put('i', Integer.toString(resolved));
        return resolved;
    }

    private void storeImage(int imageId, Bitmap bitmap) {
        Bitmap old = mImages.remove(imageId);
        if (old != null) mImagesBytes -= old.getByteCount();
        mImages.put(imageId, bitmap);
        mImagesBytes += bitmap.getByteCount();

        Iterator<Map.Entry<Integer, Bitmap>> it = mImages.entrySet().iterator();
        while (mImagesBytes > STORAGE_QUOTA_BYTES && it.hasNext()) {
            Map.Entry<Integer, Bitmap> entry = it.next();
            if (entry.getKey() == imageId) continue;
            mImagesBytes -= entry.getValue().getByteCount();
            it.remove();
        }
    }

    private void freeImage(int imageId) {
        Bitmap old = mImages.remove(imageId);
        if (old != null) mImagesBytes -= old.getByteCount();
        Iterator<Integer> it = mImageNumbers.values().iterator();
        while (it.hasNext()) {
            if (it.next() == imageId) it.remove();
        }
    }



    /* ---------- placement ---------- */

    private void place(Map<Character, String> control, int imageId, Bitmap image) throws KittyException {
        TerminalBuffer screen = mEmulator.getScreen();
        int cellWidth = Math.max(1, mEmulator.getCellWidthPixels());
        int cellHeight = Math.max(1, mEmulator.getCellHeightPixels());

        // Source rectangle.
        int srcX = getInt(control, 'x', 0), srcY = getInt(control, 'y', 0);
        int srcW = getInt(control, 'w', 0), srcH = getInt(control, 'h', 0);
        srcX = clamp(srcX, 0, image.getWidth() - 1);
        srcY = clamp(srcY, 0, image.getHeight() - 1);
        if (srcW <= 0 || srcX + srcW > image.getWidth()) srcW = image.getWidth() - srcX;
        if (srcH <= 0 || srcY + srcH > image.getHeight()) srcH = image.getHeight() - srcY;
        Bitmap bitmap = (srcX == 0 && srcY == 0 && srcW == image.getWidth() && srcH == image.getHeight()) ?
            image : Bitmap.createBitmap(image, srcX, srcY, srcW, srcH);

        // Scale to the requested number of columns / rows, keeping aspect ratio if only one is given.
        int columns = getInt(control, 'c', 0), rows = getInt(control, 'r', 0);
        int targetW = bitmap.getWidth(), targetH = bitmap.getHeight();
        if (columns > 0 && rows > 0) {
            targetW = columns * cellWidth;
            targetH = rows * cellHeight;
        } else if (columns > 0) {
            targetW = columns * cellWidth;
            targetH = Math.max(1, Math.round((float) bitmap.getHeight() * targetW / bitmap.getWidth()));
        } else if (rows > 0) {
            targetH = rows * cellHeight;
            targetW = Math.max(1, Math.round((float) bitmap.getWidth() * targetH / bitmap.getHeight()));
        }
        checkSize(targetW, targetH);
        if (targetW != bitmap.getWidth() || targetH != bitmap.getHeight()) {
            bitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
        }

        // Pixel offset inside the first cell.
        int offsetX = clamp(getInt(control, 'X', 0), 0, cellWidth - 1);
        int offsetY = clamp(getInt(control, 'Y', 0), 0, cellHeight - 1);
        if (offsetX > 0 || offsetY > 0) {
            Bitmap padded = Bitmap.createBitmap(bitmap.getWidth() + offsetX, bitmap.getHeight() + offsetY, Bitmap.Config.ARGB_8888);
            new Canvas(padded).drawBitmap(bitmap, offsetX, offsetY, null);
            bitmap = padded;
        }

        // A placement with the same image and placement id replaces the old one.
        int placementId = getInt(control, 'p', 0);
        if (imageId != 0 && placementId != 0) {
            removePlacements(p -> p.imageId == imageId && p.placementId == placementId);
        }

        int x = mEmulator.getCursorCol();
        int y = mEmulator.getCursorRow();
        int cellsWide = Math.min(mEmulator.mColumns - x, (bitmap.getWidth() + cellWidth - 1) / cellWidth);
        int cellsHigh = (bitmap.getHeight() + cellHeight - 1) / cellHeight;
        // `TerminalBitmap.buildOrThrow()` scrolls the screen when the image runs past the bottom.
        int scrolled = Math.max(0, y + cellsHigh - mEmulator.mRows);

        TerminalBitmap terminalBitmap = screen.addTerminalBitmapForKitty(bitmap, x, y, cellWidth, cellHeight);
        if (terminalBitmap == null) throw new KittyException("ENOMEM", "failed to place image");
        mPlacements.add(new Placement(imageId, placementId, terminalBitmap));

        // By default the cursor moves to the cell after the last column on the last row of the image.
        if (getInt(control, 'C', 0) != 1) {
            int row = clamp(y + cellsHigh - 1 - scrolled, 0, mEmulator.mRows - 1);
            int col = clamp(x + cellsWide, 0, mEmulator.mColumns - 1);
            mEmulator.setCursorPositionForKitty(row, col);
        }
    }

    private interface PlacementFilter {
        boolean matches(Placement placement);
    }

    /** Remove matching placements from the screen. Returns the image ids that had placements removed. */
    private List<Integer> removePlacements(PlacementFilter filter) {
        TerminalBuffer screen = mEmulator.getScreen();
        List<Integer> imageIds = new ArrayList<>();
        List<TerminalBitmap> toRemove = new ArrayList<>();
        Iterator<Placement> it = mPlacements.iterator();
        while (it.hasNext()) {
            Placement placement = it.next();
            if (!screen.isTerminalBitmapAlive(placement.terminalBitmap)) {
                // Scrolled out, garbage collected or the screen was cleared.
                it.remove();
                continue;
            }
            if (filter.matches(placement)) {
                toRemove.add(placement.terminalBitmap);
                imageIds.add(placement.imageId);
                it.remove();
            }
        }
        if (!toRemove.isEmpty()) screen.removeTerminalBitmaps(toRemove);
        return imageIds;
    }



    /* ---------- delete ---------- */

    private void delete(Map<Character, String> control) throws KittyException {
        char what = getChar(control, 'd', 'a');
        boolean freeData = Character.isUpperCase(what);
        TerminalBuffer screen = mEmulator.getScreen();
        switch (Character.toLowerCase(what)) {
            case 'a': {
                removePlacements(p -> true);
                if (freeData) {
                    mImages.clear();
                    mImagesBytes = 0;
                    mImageNumbers.clear();
                }
                break;
            }
            case 'i':
            case 'n': {
                int imageId;
                if (Character.toLowerCase(what) == 'i') {
                    imageId = getInt(control, 'i', 0);
                } else {
                    Integer resolved = mImageNumbers.get(getInt(control, 'I', 0));
                    if (resolved == null) return;
                    imageId = resolved;
                }
                int placementId = getInt(control, 'p', 0);
                removePlacements(p -> p.imageId == imageId && (placementId == 0 || p.placementId == placementId));
                if (freeData) freeImage(imageId);
                break;
            }
            case 'c':
            case 'p': {
                int col, row;
                if (Character.toLowerCase(what) == 'c') {
                    col = mEmulator.getCursorCol();
                    row = mEmulator.getCursorRow();
                } else {
                    col = getInt(control, 'x', 1) - 1;
                    row = getInt(control, 'y', 1) - 1;
                }
                TerminalBitmap atCell = screen.getTerminalBitmapAt(col, row);
                if (atCell == null) return;
                List<Integer> imageIds = removePlacements(p -> p.terminalBitmap == atCell);
                if (freeData) {
                    for (int imageId : imageIds) {
                        if (imageId != 0 && !hasPlacements(imageId)) freeImage(imageId);
                    }
                }
                break;
            }
            default:
                // Other delete targets are not supported yet, ignore silently like an unknown escape.
                break;
        }
    }

    private boolean hasPlacements(int imageId) {
        for (Placement placement : mPlacements) {
            if (placement.imageId == imageId) return true;
        }
        return false;
    }



    /* ---------- responses and parsing ---------- */

    private void respond(Map<Character, String> control, String message) {
        int imageId = getIntSafe(control, 'i');
        int imageNumber = getIntSafe(control, 'I');
        // Kitty only replies when the client gave an id or number.
        if (imageId == 0 && imageNumber == 0) return;

        int quiet = getIntSafe(control, 'q');
        boolean ok = "OK".equals(message);
        if ((ok && quiet >= 1) || (!ok && quiet >= 2)) return;

        StringBuilder response = new StringBuilder("\033_G");
        boolean first = true;
        if (imageId != 0) {
            response.append("i=").append(unsigned(imageId));
            first = false;
        }
        if (imageNumber != 0) {
            if (!first) response.append(',');
            response.append("I=").append(unsigned(imageNumber));
            first = false;
        }
        int placementId = getIntSafe(control, 'p');
        if (placementId != 0) {
            if (!first) response.append(',');
            response.append("p=").append(unsigned(placementId));
        }
        response.append(';').append(message).append("\033\\");
        mEmulator.writeToSessionForKitty(response.toString());
    }

    private static Map<Character, String> parseControl(String controlString) {
        Map<Character, String> control = new HashMap<>();
        for (String part : controlString.split(",")) {
            int eq = part.indexOf('=');
            if (eq != 1) continue;
            control.put(part.charAt(0), part.substring(2));
        }
        return control;
    }

    private static int getInt(Map<Character, String> control, char key, int defaultValue) throws KittyException {
        String value = control.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            // Ids and numbers are unsigned 32 bit, offsets may be negative.
            long parsed = Long.parseLong(value);
            return (int) parsed;
        } catch (NumberFormatException e) {
            throw new KittyException("EINVAL", "bad value for " + key);
        }
    }

    private static int getIntSafe(Map<Character, String> control, char key) {
        try {
            return getInt(control, key, 0);
        } catch (KittyException e) {
            return 0;
        }
    }

    private static char getChar(Map<Character, String> control, char key, char defaultValue) {
        String value = control.get(key);
        return value == null || value.isEmpty() ? defaultValue : value.charAt(0);
    }

    private static byte[] decodeBase64(String payload) throws KittyException {
        if (payload.isEmpty()) return new byte[0];
        try {
            return Base64.decode(payload, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new KittyException("EINVAL", "bad base64 payload");
        }
    }

    private static void appendBase64(ByteArrayOutputStream out, String payload) throws KittyException {
        byte[] decoded = decodeBase64(payload);
        out.write(decoded, 0, decoded.length);
        if (out.size() > TerminalBitmap.MAX_BITMAP_SIZE) throw new KittyException("EFBIG", "image too large");
    }

    private static String unsigned(int value) {
        return Long.toString(value & 0xffffffffL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "TerminalKitty{images=%d, bytes=%d, placements=%d}",
            mImages.size(), mImagesBytes, mPlacements.size());
    }
}
