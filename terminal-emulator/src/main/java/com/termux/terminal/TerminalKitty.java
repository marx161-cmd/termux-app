package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * Kitty terminal graphics protocol. See `scope.md` in the repo root for the decisions.
 * - https://sw.kovidgoyal.net/kitty/graphics-protocol/
 *
 * Commands arrive as `APC G <control data> ; <payload> ST` and are passed here from
 * {@link TerminalEmulator} with the leading `G` already stripped.
 *
 * Three kinds of placement:
 * - CELLS (default): stamped into the screen cells through
 *   {@link TerminalBuffer#addTerminalBitmapForKitty}, sharing scrolling/GC/drawing with sixel and iTerm.
 * - UNDER (`z<0`): drawn under the text. Anchored to the {@link TerminalRow} the cursor was on
 *   ({@link TerminalRow#mKittyAnchor}), so it scrolls with that row and dies when the row is reused.
 * - VIRTUAL (`U=1`): Unicode placeholders. Nothing is drawn until text contains U+10EEEE cells whose
 *   diacritics give row/column and whose foreground colour gives the image id (works through tmux).
 *
 * Supported: actions `t T p q d f a c`; formats 24, 32, 100 (PNG); `o=z`; chunked `m=1`; media
 * `t=d f t s` (`s` = Termux's shm_open files in `$PREFIX/tmp`); keys `i I p x y w h X Y c r C q s v S O
 * z U`; animation frames (`a=f`), control (`a=a` s/c/r/z/v) and composition (`a=c`).
 */
public class TerminalKitty {

    private static final String LOG_TAG = "TerminalKitty";

    /** Unicode placeholder character for `U=1` placements. */
    public static final int PLACEHOLDER = 0x10EEEE;

    /** Where Termux's shm_open() keeps POSIX shared memory segments (verified 2026-10-29). */
    private static final String SHM_DIR = "/data/data/com.termux/files/usr/tmp";

    /** Total decoded bytes kept for stored images before the oldest ones are evicted. */
    private static final long STORAGE_QUOTA_BYTES = 192L * 1024 * 1024;

    private static final int FORMAT_RGB = 24;
    private static final int FORMAT_RGBA = 32;
    private static final int FORMAT_PNG = 100;

    private static final int DEFAULT_GAP_MS = 40;
    private static final int ANIM_STOPPED = 1, ANIM_LOADING = 2, ANIM_RUNNING = 3;

    private final TerminalEmulator mEmulator;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAnimationTick = this::animationTick;
    private boolean mAnimationScheduled;
    private boolean mReleased;

    /** A stored image: its root frame plus any animation frames. */
    private static final class Image {
        final List<Bitmap> frames = new ArrayList<>();
        /** Per-frame gap in ms; negative = gapless (skipped). */
        final List<Integer> gaps = new ArrayList<>();
        int current;
        int state = ANIM_STOPPED;
        /** Plays left, 0 = forever. */
        int loops;
        long nextAt;

        Image(Bitmap root) {
            frames.add(root);
            gaps.add(DEFAULT_GAP_MS);
        }

        Bitmap currentFrame() {
            return frames.get(Math.min(current, frames.size() - 1));
        }

        long bytes() {
            long total = 0;
            for (Bitmap frame : frames) total += frame.getByteCount();
            return total;
        }
    }

    /** Stored images by image id, in insertion order for eviction. */
    private final LinkedHashMap<Integer, Image> mImages = new LinkedHashMap<>();
    private long mImagesBytes;
    /** Image number (`I=`) to the newest image id that was assigned for it. */
    private final HashMap<Integer, Integer> mImageNumbers = new HashMap<>();
    private int mNextAutoImageId = 0x7f000000;

    private static final int KIND_CELLS = 0, KIND_UNDER = 1, KIND_VIRTUAL = 2;

    /** How a placement turns an image frame into what is drawn. */
    private static final class Params {
        int srcX, srcY, srcW, srcH, columns, rows, offsetX, offsetY;
    }

    private static final class Placement {
        final int kind;
        final int imageId;
        final int placementId;
        final Params params;
        TerminalBitmap terminalBitmap; // CELLS
        long anchor;                   // UNDER
        int column;                    // UNDER
        int cellRows;                  // UNDER: rows covered
        Bitmap rendered;               // UNDER, VIRTUAL
        int renderedCellW, renderedCellH, renderedFrame = -1; // VIRTUAL cache key

        Placement(int kind, int imageId, int placementId, Params params) {
            this.kind = kind;
            this.imageId = imageId;
            this.placementId = placementId;
            this.params = params;
        }
    }
    private final List<Placement> mPlacements = new ArrayList<>();
    private final HashMap<Long, Placement> mUnderlays = new HashMap<>();
    private long mNextAnchor = 1;

    /** Control keys of the first chunk of a chunked (`m=1`) transmission in progress. */
    private Map<Character, String> mChunkedControl;
    private ByteArrayOutputStream mChunkedData;

    public TerminalKitty(TerminalEmulator emulator) {
        mEmulator = emulator;
    }

    /** Stop the animation timer (the emulator was reset). */
    void release() {
        mReleased = true;
        mHandler.removeCallbacks(mAnimationTick);
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
                if (action != 't' && action != 'T' && action != 'q' && action != 'f') {
                    throw new KittyException("EINVAL", "chunking is only supported for transmit, query and frames");
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
                Image image = new Image(bitmap);
                if (imageId != 0) storeImage(imageId, image);
                if (action == 'T') place(control, imageId, image);
                respond(control, "OK");
                return;
            }
            case 'p': {
                int imageId = resolveImageId(control);
                Image image = mImages.get(imageId);
                if (image == null) throw new KittyException("ENOENT", "no image with id " + imageId);
                place(control, imageId, image);
                respond(control, "OK");
                return;
            }
            case 'd':
                delete(control);
                return;
            case 'f':
                frame(control, payload);
                respond(control, "OK");
                return;
            case 'a':
                animate(control);
                respond(control, "OK");
                return;
            case 'c':
                compose(control);
                respond(control, "OK");
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
                data = readFile(new String(payload, java.nio.charset.StandardCharsets.UTF_8), medium == 't' ? 1 : 0,
                    getInt(control, 'O', 0), getInt(control, 'S', 0));
                break;
            case 's': {
                // Termux's shm_open() backs `/name` with the file $PREFIX/tmp/name.
                String name = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.contains("/") || name.equals("..") || name.equals(".")) {
                    throw new KittyException("EINVAL", "bad shared memory name");
                }
                data = readFile(SHM_DIR + "/" + name, 2, getInt(control, 'O', 0), getInt(control, 'S', 0));
                break;
            }
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

    /** @param deleteMode 0 = keep (t=f), 1 = temp file checks + delete (t=t), 2 = delete (t=s). */
    private static byte[] readFile(String path, int deleteMode, int offset, int size) throws KittyException {
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
        if (deleteMode == 1 && !(canonical.contains("tty-graphics-protocol") && canonical.contains("/tmp/"))) {
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
            throw new KittyException(deleteMode == 2 ? "ENOENT" : "EBADF", "failed to read " + (deleteMode == 2 ? "shared memory" : "file"));
        } finally {
            if (deleteMode != 0) //noinspection ResultOfMethodCallIgnored
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

    private Image requireImage(Map<Character, String> control) throws KittyException {
        int imageId = resolveImageId(control);
        Image image = mImages.get(imageId);
        if (image == null) throw new KittyException("ENOENT", "no image with id " + imageId);
        return image;
    }

    private void storeImage(int imageId, Image image) {
        Image old = mImages.remove(imageId);
        if (old != null) mImagesBytes -= old.bytes();
        mImages.put(imageId, image);
        mImagesBytes += image.bytes();
        evict(imageId);
    }

    private void evict(int keepImageId) {
        Iterator<Map.Entry<Integer, Image>> it = mImages.entrySet().iterator();
        while (mImagesBytes > STORAGE_QUOTA_BYTES && it.hasNext()) {
            Map.Entry<Integer, Image> entry = it.next();
            if (entry.getKey() == keepImageId) continue;
            mImagesBytes -= entry.getValue().bytes();
            it.remove();
        }
    }

    private void freeImage(int imageId) {
        Image old = mImages.remove(imageId);
        if (old != null) mImagesBytes -= old.bytes();
        Iterator<Integer> it = mImageNumbers.values().iterator();
        while (it.hasNext()) {
            if (it.next() == imageId) it.remove();
        }
    }



    /* ---------- placement ---------- */

    private Params readParams(Map<Character, String> control) throws KittyException {
        Params params = new Params();
        params.srcX = getInt(control, 'x', 0);
        params.srcY = getInt(control, 'y', 0);
        params.srcW = getInt(control, 'w', 0);
        params.srcH = getInt(control, 'h', 0);
        params.columns = getInt(control, 'c', 0);
        params.rows = getInt(control, 'r', 0);
        params.offsetX = getInt(control, 'X', 0);
        params.offsetY = getInt(control, 'Y', 0);
        return params;
    }

    private static Bitmap crop(Bitmap image, Params p) {
        int srcX = clamp(p.srcX, 0, image.getWidth() - 1);
        int srcY = clamp(p.srcY, 0, image.getHeight() - 1);
        int srcW = p.srcW <= 0 || srcX + p.srcW > image.getWidth() ? image.getWidth() - srcX : p.srcW;
        int srcH = p.srcH <= 0 || srcY + p.srcH > image.getHeight() ? image.getHeight() - srcY : p.srcH;
        return (srcX == 0 && srcY == 0 && srcW == image.getWidth() && srcH == image.getHeight()) ?
            image : Bitmap.createBitmap(image, srcX, srcY, srcW, srcH);
    }

    /** Crop, scale to `c`/`r` cells and offset by `X`/`Y` pixels: what CELLS and UNDER placements draw. */
    private static Bitmap transform(Bitmap image, Params p, int cellWidth, int cellHeight) throws KittyException {
        Bitmap bitmap = crop(image, p);
        int targetW = bitmap.getWidth(), targetH = bitmap.getHeight();
        if (p.columns > 0 && p.rows > 0) {
            targetW = p.columns * cellWidth;
            targetH = p.rows * cellHeight;
        } else if (p.columns > 0) {
            targetW = p.columns * cellWidth;
            targetH = Math.max(1, Math.round((float) bitmap.getHeight() * targetW / bitmap.getWidth()));
        } else if (p.rows > 0) {
            targetH = p.rows * cellHeight;
            targetW = Math.max(1, Math.round((float) bitmap.getWidth() * targetH / bitmap.getHeight()));
        }
        checkSize(targetW, targetH);
        if (targetW != bitmap.getWidth() || targetH != bitmap.getHeight()) {
            bitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
        }
        int offsetX = clamp(p.offsetX, 0, cellWidth - 1);
        int offsetY = clamp(p.offsetY, 0, cellHeight - 1);
        if (offsetX > 0 || offsetY > 0) {
            Bitmap padded = Bitmap.createBitmap(bitmap.getWidth() + offsetX, bitmap.getHeight() + offsetY, Bitmap.Config.ARGB_8888);
            new Canvas(padded).drawBitmap(bitmap, offsetX, offsetY, null);
            bitmap = padded;
        }
        return bitmap;
    }

    private void place(Map<Character, String> control, int imageId, Image image) throws KittyException {
        int cellWidth = Math.max(1, mEmulator.getCellWidthPixels());
        int cellHeight = Math.max(1, mEmulator.getCellHeightPixels());
        Params params = readParams(control);
        int placementId = getInt(control, 'p', 0);

        // A placement with the same image and placement id replaces the old one.
        if (imageId != 0 && placementId != 0) {
            removePlacements(p -> p.imageId == imageId && p.placementId == placementId);
        }

        if (getInt(control, 'U', 0) == 1) {
            // Virtual placement for Unicode placeholders: size the cell grid, draw nothing yet.
            if (imageId == 0) throw new KittyException("EINVAL", "virtual placements need an image id");
            Bitmap cropped = crop(image.currentFrame(), params);
            if (params.columns <= 0 && params.rows <= 0) {
                params.columns = Math.max(1, (cropped.getWidth() + cellWidth - 1) / cellWidth);
                params.rows = Math.max(1, (cropped.getHeight() + cellHeight - 1) / cellHeight);
            } else if (params.columns <= 0) {
                params.columns = Math.max(1, Math.round((float) params.rows * cellHeight * cropped.getWidth() / cropped.getHeight() / cellWidth));
            } else if (params.rows <= 0) {
                params.rows = Math.max(1, Math.round((float) params.columns * cellWidth * cropped.getHeight() / cropped.getWidth() / cellHeight));
            }
            mPlacements.add(new Placement(KIND_VIRTUAL, imageId, placementId, params));
            return;
        }

        Bitmap bitmap = transform(image.currentFrame(), params, cellWidth, cellHeight);
        int x = mEmulator.getCursorCol();
        int y = mEmulator.getCursorRow();
        int cellsWide = Math.min(mEmulator.mColumns - x, (bitmap.getWidth() + cellWidth - 1) / cellWidth);
        int cellsHigh = (bitmap.getHeight() + cellHeight - 1) / cellHeight;
        boolean moveCursor = getInt(control, 'C', 0) != 1;

        if (getInt(control, 'z', 0) < 0) {
            // Under the text: anchor to the cursor's row so it scrolls with it.
            pruneUnderlays();
            TerminalBuffer screen = mEmulator.getScreen();
            TerminalRow row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(y));
            Placement placement = new Placement(KIND_UNDER, imageId, placementId, params);
            placement.anchor = mNextAnchor++;
            placement.column = x;
            placement.cellRows = cellsHigh;
            placement.rendered = bitmap;
            row.mKittyAnchor = placement.anchor;
            mUnderlays.put(placement.anchor, placement);
            mPlacements.add(placement);
            if (moveCursor) {
                // Like the cell placements: end on the image's last row, after its last column.
                for (int i = 1; i < cellsHigh; i++) mEmulator.linefeedForKitty();
                mEmulator.setCursorPositionForKitty(mEmulator.getCursorRow(), clamp(x + cellsWide, 0, mEmulator.mColumns - 1));
            }
            return;
        }

        // `TerminalBitmap.buildOrThrow()` scrolls the screen when the image runs past the bottom.
        int scrolled = Math.max(0, y + cellsHigh - mEmulator.mRows);
        TerminalBitmap terminalBitmap = mEmulator.getScreen().addTerminalBitmapForKitty(bitmap, x, y, cellWidth, cellHeight);
        if (terminalBitmap == null) throw new KittyException("ENOMEM", "failed to place image");
        Placement placement = new Placement(KIND_CELLS, imageId, placementId, params);
        placement.terminalBitmap = terminalBitmap;
        mPlacements.add(placement);

        // By default the cursor moves to the cell after the last column on the last row of the image.
        if (moveCursor) {
            int row = clamp(y + cellsHigh - 1 - scrolled, 0, mEmulator.mRows - 1);
            int col = clamp(x + cellsWide, 0, mEmulator.mColumns - 1);
            mEmulator.setCursorPositionForKitty(row, col);
        }
    }

    /** Forget underlays whose anchor row was cleared, reused or scrolled out of the transcript. */
    private void pruneUnderlays() {
        if (mUnderlays.isEmpty()) return;
        java.util.HashSet<Long> alive = new java.util.HashSet<>();
        for (TerminalBuffer buffer : mEmulator.buffersForKitty()) {
            for (TerminalRow row : buffer.mLines) {
                if (row != null && row.mKittyAnchor != 0) alive.add(row.mKittyAnchor);
            }
        }
        Iterator<Map.Entry<Long, Placement>> it = mUnderlays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Placement> entry = it.next();
            if (!alive.contains(entry.getKey())) {
                mPlacements.remove(entry.getValue());
                it.remove();
            }
        }
    }

    private boolean isAlive(Placement placement) {
        switch (placement.kind) {
            case KIND_CELLS: return mEmulator.getScreen().isTerminalBitmapAlive(placement.terminalBitmap);
            case KIND_UNDER: return mUnderlays.containsKey(placement.anchor);
            default: return true;
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
            if (!isAlive(placement)) {
                // Scrolled out, garbage collected or the screen was cleared.
                it.remove();
                continue;
            }
            if (filter.matches(placement)) {
                if (placement.kind == KIND_CELLS) toRemove.add(placement.terminalBitmap);
                if (placement.kind == KIND_UNDER) mUnderlays.remove(placement.anchor);
                imageIds.add(placement.imageId);
                it.remove();
            }
        }
        if (!toRemove.isEmpty()) screen.removeTerminalBitmaps(toRemove);
        return imageIds;
    }



    /* ---------- drawing hooks for the renderer ---------- */

    /** The bitmap drawn under the text for the row anchored with `anchor`, or null. */
    public Bitmap underlayBitmap(long anchor) {
        Placement placement = mUnderlays.get(anchor);
        return placement == null ? null : placement.rendered;
    }

    public int underlayColumn(long anchor) {
        Placement placement = mUnderlays.get(anchor);
        return placement == null ? 0 : placement.column;
    }

    /** The most rows any underlay covers, so the renderer can look a little above the visible area. */
    public int maxUnderlayRows() {
        int max = 0;
        for (Placement placement : mUnderlays.values()) max = Math.max(max, placement.cellRows);
        return max;
    }

    public boolean hasUnderlays() {
        return !mUnderlays.isEmpty();
    }

    /** The number a placeholder diacritic encodes, or -1. */
    public static int diacriticValue(int codePoint) {
        return TerminalKittyDiacritics.valueOf(codePoint);
    }

    /**
     * For a placeholder cell of `imageId` at image `row`/`col`, return the bitmap and fill `outSrc`
     * with the slice to draw into the cell, or return null if there is no such virtual placement.
     */
    public Bitmap placeholderSlice(int imageId, int placementId, int row, int col, int cellW, int cellH, Rect outSrc) {
        Placement placement = null;
        for (int i = mPlacements.size() - 1; i >= 0; i--) {
            Placement p = mPlacements.get(i);
            if (p.kind == KIND_VIRTUAL && p.imageId == imageId && (placementId == 0 || p.placementId == placementId)) {
                placement = p;
                break;
            }
        }
        Image image = mImages.get(imageId);
        if (placement == null || image == null) return null;
        Params params = placement.params;
        if (row < 0 || col < 0 || row >= params.rows || col >= params.columns) return null;

        if (placement.rendered == null || placement.renderedCellW != cellW || placement.renderedCellH != cellH
            || placement.renderedFrame != image.current) {
            // Fit the image into the c x r cell grid, keeping its aspect ratio, centred (as kitty does).
            Bitmap cropped = crop(image.currentFrame(), params);
            int gridW = params.columns * cellW, gridH = params.rows * cellH;
            try {
                checkSize(gridW, gridH);
            } catch (KittyException e) {
                return null;
            }
            float scale = Math.min((float) gridW / cropped.getWidth(), (float) gridH / cropped.getHeight());
            int w = Math.max(1, Math.round(cropped.getWidth() * scale)), h = Math.max(1, Math.round(cropped.getHeight() * scale));
            Bitmap grid = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888);
            new Canvas(grid).drawBitmap(Bitmap.createScaledBitmap(cropped, w, h, true), (gridW - w) / 2f, (gridH - h) / 2f, null);
            placement.rendered = grid;
            placement.renderedCellW = cellW;
            placement.renderedCellH = cellH;
            placement.renderedFrame = image.current;
        }
        outSrc.set(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH);
        return placement.rendered;
    }



    /* ---------- animation ---------- */

    /** `a=f`: add a frame, or edit one (`r`), from transmitted data. */
    private void frame(Map<Character, String> control, byte[] payload) throws KittyException {
        Image image = requireImage(control);
        Bitmap data = loadImage(control, payload);
        Bitmap root = image.frames.get(0);
        int editFrame = getInt(control, 'r', 0);
        int baseFrame = getInt(control, 'c', 0);

        Bitmap canvasBitmap;
        if (editFrame > 0) {
            if (editFrame > image.frames.size()) throw new KittyException("ENOENT", "no frame " + editFrame);
            canvasBitmap = image.frames.get(editFrame - 1).copy(Bitmap.Config.ARGB_8888, true);
        } else if (baseFrame > 0) {
            if (baseFrame > image.frames.size()) throw new KittyException("ENOENT", "no frame " + baseFrame);
            canvasBitmap = image.frames.get(baseFrame - 1).copy(Bitmap.Config.ARGB_8888, true);
        } else {
            canvasBitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            int rgba = getInt(control, 'Y', 0);
            if (rgba != 0) canvasBitmap.eraseColor((rgba >>> 8) | (rgba << 24)); // RGBA -> ARGB
        }

        Paint paint = new Paint();
        if (getInt(control, 'X', 0) == 1) paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        new Canvas(canvasBitmap).drawBitmap(data, getInt(control, 'x', 0), getInt(control, 'y', 0), paint);

        int gap = getInt(control, 'z', 0);
        mImagesBytes -= image.bytes();
        if (editFrame > 0) {
            image.frames.set(editFrame - 1, canvasBitmap);
            if (control.containsKey('z')) image.gaps.set(editFrame - 1, gap == 0 ? DEFAULT_GAP_MS : gap);
        } else {
            image.frames.add(canvasBitmap);
            image.gaps.add(gap == 0 ? DEFAULT_GAP_MS : gap);
        }
        mImagesBytes += image.bytes();
        evict(resolveImageId(control));

        if (editFrame - 1 == image.current) refreshPlacements(resolveImageId(control), image);
        scheduleAnimation();
    }

    /** `a=a`: animation control. */
    private void animate(Map<Character, String> control) throws KittyException {
        int imageId = resolveImageId(control);
        Image image = requireImage(control);
        int gapFrame = getInt(control, 'r', 0);
        if (gapFrame > 0 && gapFrame <= image.frames.size() && control.containsKey('z')) {
            int gap = getInt(control, 'z', 0);
            image.gaps.set(gapFrame - 1, gap == 0 ? DEFAULT_GAP_MS : gap);
        }
        int loops = getInt(control, 'v', 0);
        if (loops == 1) image.loops = 0;          // forever
        else if (loops > 1) image.loops = loops - 1;
        int currentFrame = getInt(control, 'c', 0);
        if (currentFrame > 0 && currentFrame <= image.frames.size()) {
            image.current = currentFrame - 1;
            refreshPlacements(imageId, image);
        }
        int state = getInt(control, 's', 0);
        if (state >= ANIM_STOPPED && state <= ANIM_RUNNING) {
            if (image.state == ANIM_STOPPED && state != ANIM_STOPPED) {
                image.nextAt = SystemClock.uptimeMillis() + Math.max(1, image.gaps.get(image.current));
            }
            image.state = state;
        }
        scheduleAnimation();
    }

    /** `a=c`: copy a rectangle from frame `r` onto frame `c`. */
    private void compose(Map<Character, String> control) throws KittyException {
        int imageId = resolveImageId(control);
        Image image = requireImage(control);
        int src = getInt(control, 'r', 1), dst = getInt(control, 'c', 1);
        if (src < 1 || src > image.frames.size() || dst < 1 || dst > image.frames.size()) {
            throw new KittyException("ENOENT", "no such frame");
        }
        Bitmap source = image.frames.get(src - 1);
        int x = getInt(control, 'x', 0), y = getInt(control, 'y', 0);
        int w = getInt(control, 'w', 0), h = getInt(control, 'h', 0);
        if (w <= 0) w = source.getWidth() - x;
        if (h <= 0) h = source.getHeight() - y;
        Bitmap dest = image.frames.get(dst - 1).copy(Bitmap.Config.ARGB_8888, true);
        Paint paint = new Paint();
        if (getInt(control, 'C', 0) == 1) paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        int dx = getInt(control, 'X', 0), dy = getInt(control, 'Y', 0);
        new Canvas(dest).drawBitmap(source, new Rect(x, y, x + w, y + h), new Rect(dx, dy, dx + w, dy + h), paint);
        image.frames.set(dst - 1, dest);
        if (dst - 1 == image.current) refreshPlacements(imageId, image);
    }

    private void scheduleAnimation() {
        if (mReleased) return;
        long next = Long.MAX_VALUE;
        for (Image image : mImages.values()) {
            if (image.state != ANIM_STOPPED && image.frames.size() > 1) next = Math.min(next, image.nextAt);
        }
        mHandler.removeCallbacks(mAnimationTick);
        mAnimationScheduled = next != Long.MAX_VALUE;
        if (mAnimationScheduled) {
            mHandler.postDelayed(mAnimationTick, Math.max(1, next - SystemClock.uptimeMillis()));
        }
    }

    private void animationTick() {
        if (mReleased) return;
        long now = SystemClock.uptimeMillis();
        boolean changed = false;
        for (Map.Entry<Integer, Image> entry : mImages.entrySet()) {
            Image image = entry.getValue();
            if (image.state == ANIM_STOPPED || image.frames.size() < 2 || now < image.nextAt) continue;
            int last = image.frames.size() - 1;
            int next = image.current;
            for (int guard = 0; guard <= last; guard++) {
                if (next == last) {
                    if (image.state == ANIM_LOADING) break;        // wait for more frames
                    if (image.loops > 0 && --image.loops == 0) {   // that was the last play
                        image.state = ANIM_STOPPED;
                        break;
                    }
                    next = 0;
                } else {
                    next++;
                }
                if (image.gaps.get(next) >= 0) break;              // negative gap = gapless, skip
            }
            if (next != image.current) {
                image.current = next;
                refreshPlacements(entry.getKey(), image);
                changed = true;
            }
            image.nextAt = now + Math.max(1, image.gaps.get(image.current));
        }
        if (changed) mEmulator.requestRedrawForKitty();
        scheduleAnimation();
    }

    /** Re-render every placement of `image` from its current frame. */
    private void refreshPlacements(int imageId, Image image) {
        int cellWidth = Math.max(1, mEmulator.getCellWidthPixels());
        int cellHeight = Math.max(1, mEmulator.getCellHeightPixels());
        for (Placement placement : mPlacements) {
            if (placement.imageId != imageId || !isAlive(placement)) continue;
            try {
                switch (placement.kind) {
                    case KIND_CELLS: {
                        Bitmap old = placement.terminalBitmap.getBitmap();
                        Bitmap next = transform(image.currentFrame(), placement.params, cellWidth, cellHeight);
                        if (old != null && (next.getWidth() > old.getWidth() || next.getHeight() > old.getHeight())) {
                            next = Bitmap.createBitmap(next, 0, 0, Math.min(next.getWidth(), old.getWidth()),
                                Math.min(next.getHeight(), old.getHeight()));
                        }
                        placement.terminalBitmap.replaceBitmapForKitty(next);
                        break;
                    }
                    case KIND_UNDER:
                        placement.rendered = transform(image.currentFrame(), placement.params, cellWidth, cellHeight);
                        break;
                    case KIND_VIRTUAL:
                        placement.renderedFrame = -1; // re-rendered lazily by placeholderSlice()
                        break;
                }
            } catch (KittyException ignored) {
            }
        }
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
            case 'z': {
                // Placements with the given z-index: only the "under the text" layer is tracked by z.
                int z = getInt(control, 'z', 0);
                if (z < 0) removePlacements(p -> p.kind == KIND_UNDER);
                break;
            }
            default:
                // Other delete targets are not supported, ignore silently like an unknown escape.
                break;
        }
        mEmulator.requestRedrawForKitty();
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
        return String.format(Locale.US, "TerminalKitty{images=%d, bytes=%d, placements=%d, underlays=%d}",
            mImages.size(), mImagesBytes, mPlacements.size(), mUnderlays.size());
    }
}
