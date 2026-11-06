package com.deh.mobile;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/** 仿 MT 文件提供器的四目录 DocumentsProvider：内部存储 / 外置存储 / 应用私有 / 系统根。 */
public class DshDocumentsProvider extends DocumentsProvider {
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DEFAULT_DOC_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
    };

    private static final class RootDef {
        final String id;
        final String title;
        final String path;
        final int flags;

        RootDef(String id, String title, String path, int flags) {
            this.id = id;
            this.title = title;
            this.path = path;
            this.flags = flags;
        }
    }

    private List<RootDef> roots() {
        List<RootDef> list = new ArrayList<>();
        list.add(new RootDef("storage", "内部存储", "/storage/emulated/0",
                Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD));
        try {
            File storage = new File("/storage");
            File[] vols = storage.listFiles();
            if (vols != null) {
                for (File v : vols) {
                    String name = v.getName();
                    if ("emulated".equals(name) || "self".equals(name)) continue;
                    if (!v.isDirectory()) continue;
                    list.add(new RootDef("vol_" + name, "外置存储 · " + name, v.getAbsolutePath(),
                            Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD));
                }
            }
        } catch (Throwable ignored) {
        }
        list.add(new RootDef("app", "应用私有目录", getContext().getDataDir().getAbsolutePath(),
                Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD));
        list.add(new RootDef("root", "系统根目录", "/", Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE));
        return list;
    }

    private RootDef findRoot(String rootId) {
        for (RootDef r : roots()) {
            if (r.id.equals(rootId)) return r;
        }
        return null;
    }

    /** documentId 格式：rootId:相对路径（根目录为 "rootId:"）。 */
    private File resolve(String documentId) {
        if (documentId == null) documentId = "storage:";
        int idx = documentId.indexOf(':');
        String rootId = idx >= 0 ? documentId.substring(0, idx) : documentId;
        String rel = idx >= 0 ? documentId.substring(idx + 1) : "";
        RootDef root = findRoot(rootId);
        if (root == null) root = findRoot("storage");
        if (root == null) return new File("/storage/emulated/0");
        if (rel.isEmpty()) return new File(root.path);
        return new File(root.path, rel);
    }

    private String docId(File file) {
        String path = file.getAbsolutePath();
        RootDef best = null;
        for (RootDef r : roots()) {
            if (path.equals(r.path) || path.startsWith(r.path + "/")) {
                if (best == null || r.path.length() > best.path.length()) best = r;
            }
        }
        if (best == null) return "storage:";
        String rel = path.equals(best.path) ? "" : path.substring(best.path.length() + 1);
        return best.id + ":" + rel;
    }

    private String mime(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        String[] cols = projection != null ? projection : DEFAULT_ROOT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(cols);
        for (RootDef root : roots()) {
            File dir = new File(root.path);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String col : cols) {
                switch (col) {
                    case Root.COLUMN_ROOT_ID: row.add(root.id); break;
                    case Root.COLUMN_DOCUMENT_ID: row.add(root.id + ":"); break;
                    case Root.COLUMN_TITLE: row.add(root.title); break;
                    case Root.COLUMN_FLAGS: row.add(root.flags); break;
                    case Root.COLUMN_ICON: row.add(R.mipmap.ic_launcher); break;
                    case Root.COLUMN_AVAILABLE_BYTES: row.add(dir.exists() ? dir.getUsableSpace() : 0); break;
                    default: row.add(null); break;
                }
            }
        }
        return cursor;
    }

    private void addDocument(MatrixCursor cursor, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(docId(file));
        row.add(file.getName());
        row.add(file.isDirectory() ? 0 : file.length());
        row.add(mime(file));
        row.add(file.lastModified());
        int flags = file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE
                : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        row.add(flags);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        File file = resolve(documentId);
        if (!file.exists()) throw new FileNotFoundException(documentId);
        String[] cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(cols);
        addDocument(cursor, file);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (!parent.exists() || !parent.isDirectory()) throw new FileNotFoundException(parentDocumentId);
        String[] cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(cols);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) addDocument(cursor, child);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = resolve(documentId);
        if (!file.exists()) throw new FileNotFoundException(documentId);
        int flags = mode.startsWith("r") ? ParcelFileDescriptor.MODE_READ_ONLY
                : ParcelFileDescriptor.MODE_READ_WRITE;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        File file = new File(parent, displayName);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                file.mkdirs();
            } else {
                file.createNewFile();
            }
        } catch (Exception e) {
            throw new FileNotFoundException("create failed: " + e);
        }
        return docId(file);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = resolve(documentId);
        if (!file.exists()) throw new FileNotFoundException(documentId);
        deleteRecursive(file);
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        file.delete();
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = resolve(documentId);
        File target = new File(file.getParentFile(), displayName);
        if (file.renameTo(target)) {
            return docId(target);
        }
        throw new FileNotFoundException("rename failed");
    }
}
