package com.pestage.editor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Выбор папки через системный диалог (SAF) и запись в неё файлов.
 * Методы: pick(), check({tree}), listDirs({tree}), writeFile({tree, path, data(base64)}).
 */
@CapacitorPlugin(name = "FolderPicker")
public class FolderPickerPlugin extends Plugin {

    @PluginMethod
    public void pick(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        startActivityForResult(call, intent, "pickResult");
    }

    @ActivityCallback
    private void pickResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("cancelled");
            return;
        }
        Uri tree = result.getData().getData();
        try {
            getContext()
                .getContentResolver()
                .takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            // разрешение на эту сессию всё равно выдано
        }
        JSObject ret = new JSObject();
        ret.put("uri", tree.toString());
        ret.put("name", rootName(tree));
        call.resolve(ret);
    }

    @PluginMethod
    public void check(PluginCall call) {
        String tree = call.getString("tree");
        boolean ok = false;
        if (tree != null) {
            Uri u = Uri.parse(tree);
            for (UriPermission p : getContext().getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u) && p.isWritePermission()) {
                    ok = true;
                    break;
                }
            }
        }
        JSObject ret = new JSObject();
        ret.put("ok", ok);
        call.resolve(ret);
    }

    @PluginMethod
    public void listDirs(PluginCall call) {
        String tree = call.getString("tree");
        if (tree == null) {
            call.reject("tree is required");
            return;
        }
        Cursor c = null;
        try {
            Uri t = Uri.parse(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
            List<String> names = new ArrayList<>();
            c = getContext().getContentResolver().query(
                children,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE },
                null,
                null,
                null
            );
            while (c != null && c.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1))) {
                    names.add(c.getString(0));
                }
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            JSArray arr = new JSArray();
            for (String n : names) {
                arr.put(n);
            }
            JSObject ret = new JSObject();
            ret.put("dirs", arr);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("list failed: " + e.getMessage());
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String tree = call.getString("tree");
        String path = call.getString("path");
        String data = call.getString("data");
        if (tree == null || path == null || data == null) {
            call.reject("tree, path and data are required");
            return;
        }
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            writeTo(Uri.parse(tree), path, bytes);
            call.resolve();
        } catch (Exception e) {
            call.reject("write failed: " + path + " (" + e.getMessage() + ")");
        }
    }

    private String rootName(Uri tree) {
        Cursor c = null;
        try {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            c = getContext().getContentResolver().query(
                root,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                null,
                null,
                null
            );
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) {
                    return n;
                }
            }
        } catch (Exception e) {
            // ниже вернём запасное имя
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return "folder";
    }

    private Uri findChild(Uri tree, Uri parentDoc, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc));
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(
                children,
                new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                null,
                null,
                null
            );
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private Uri ensureDir(Uri tree, Uri parent, String name) throws Exception {
        Uri existing = findChild(tree, parent, name);
        if (existing != null) {
            return existing;
        }
        Uri created = DocumentsContract.createDocument(
            getContext().getContentResolver(),
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        );
        if (created == null) {
            throw new Exception("cannot create folder " + name);
        }
        return created;
    }

    private String mimeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    private void writeTo(Uri tree, String relPath, byte[] bytes) throws Exception {
        List<String> parts = new ArrayList<>();
        for (String p : relPath.split("/")) {
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals("..") || p.equals(".")) {
                throw new Exception("bad path");
            }
            parts.add(p);
        }
        if (parts.isEmpty()) {
            throw new Exception("empty path");
        }
        ContentResolver resolver = getContext().getContentResolver();
        Uri cur = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        for (int i = 0; i < parts.size() - 1; i++) {
            cur = ensureDir(tree, cur, parts.get(i));
        }
        String fileName = parts.get(parts.size() - 1);
        Uri file = findChild(tree, cur, fileName);
        if (file == null) {
            file = DocumentsContract.createDocument(resolver, cur, mimeFor(fileName), fileName);
        }
        if (file == null) {
            throw new Exception("cannot create file " + fileName);
        }
        OutputStream os = resolver.openOutputStream(file, "wt");
        if (os == null) {
            throw new Exception("cannot open " + fileName);
        }
        try {
            os.write(bytes);
        } finally {
            os.close();
        }
    }
}
