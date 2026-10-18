package ts.realms.m2git.local.preference;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;

import timber.log.Timber;
import ts.realms.m2git.R;

/**
 * 文件内容查看页（CodeMirror）的显示选项。
 *
 * <p>两个层次：
 * <ul>
 *   <li><b>全局默认</b>：落在 {@code ts.realms.m2git} 偏好文件里（设置页「文件显示」分组）。
 *       该文件被 {@code BackupManager} 全量 {@code getAll()} 导出，所以新键自动进设置备份，
 *       导入旧备份时键缺失即回落默认值，无需迁移。</li>
 *   <li><b>单文件临时覆盖</b>：只存在 {@link ts.realms.m2git.ui.components.fragments.ViewFileFragment}
 *       的实例字段里，不写盘，退出页面即失效。语义与既有的「选择语言」一致。</li>
 * </ul>
 *
 * <p>该类同时是 JS 侧契约：{@link #toJson()} 的输出直接喂给 {@code editor.js} 的
 * {@code window.applyViewOptions()}，字段名两边必须一致。
 *
 * <p>默认值刻意等于改造前 editor.js 里硬编码的那组（行号/换行/括号匹配全开、字号跟随、
 * Tab 宽 4），保证升级后视觉零变化。
 */
public final class FileViewOptions implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 字号取该值表示「跟随 WebView 默认」，即不向 CodeMirror 注入 font-size。 */
    public static final int FONT_SIZE_FOLLOW_DEFAULT = 0;

    /** CodeMirror 5 的默认 Tab 宽度；显式写出来是为了让 JSON 契约始终有值。 */
    public static final int DEFAULT_TAB_SIZE = 4;

    public boolean lineNumbers = true;
    public boolean lineWrapping = true;
    public boolean matchBrackets = true;
    public int fontSizeSp = FONT_SIZE_FOLLOW_DEFAULT;
    public int tabSize = DEFAULT_TAB_SIZE;

    public static FileViewOptions readGlobal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            context.getString(R.string.preference_file_key), Context.MODE_PRIVATE);

        FileViewOptions options = new FileViewOptions();
        options.lineNumbers = prefs.getBoolean(
            key(context, R.string.pref_key_viewer_line_numbers), options.lineNumbers);
        options.lineWrapping = prefs.getBoolean(
            key(context, R.string.pref_key_viewer_line_wrap), options.lineWrapping);
        options.matchBrackets = prefs.getBoolean(
            key(context, R.string.pref_key_viewer_match_brackets), options.matchBrackets);
        // ListPreference 存的是字符串，这里统一按字符串解析：未来若改成 SeekBarPreference(Int)
        // 只会拿到默认值而不是崩溃，方向上是安全的降级。
        options.fontSizeSp = parseNonNegativeInt(prefs.getString(
            key(context, R.string.pref_key_viewer_font_size), null), options.fontSizeSp);
        options.tabSize = parseNonNegativeInt(prefs.getString(
            key(context, R.string.pref_key_viewer_tab_size), null), options.tabSize);
        return options;
    }

    public FileViewOptions copy() {
        FileViewOptions copy = new FileViewOptions();
        copy.lineNumbers = lineNumbers;
        copy.lineWrapping = lineWrapping;
        copy.matchBrackets = matchBrackets;
        copy.fontSizeSp = fontSizeSp;
        copy.tabSize = tabSize;
        return copy;
    }

    /** 逐字段比较。onResume 用它判断「全局值是否被别人改过」，不依赖 equals 语义。 */
    public boolean sameAs(FileViewOptions other) {
        return other != null
            && lineNumbers == other.lineNumbers
            && lineWrapping == other.lineWrapping
            && matchBrackets == other.matchBrackets
            && fontSizeSp == other.fontSizeSp
            && tabSize == other.tabSize;
    }

    /** 给 JS 的 JSON；布尔/整数不会抛 JSONException，catch 只为满足编译期检查。 */
    public String toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("lineNumbers", lineNumbers);
            json.put("lineWrapping", lineWrapping);
            json.put("matchBrackets", matchBrackets);
            json.put("fontSizeSp", fontSizeSp);
            json.put("tabSize", tabSize);
        } catch (JSONException e) {
            Timber.e(e, "FileViewOptions.toJson");
        }
        return json.toString();
    }

    private static String key(Context context, int resId) {
        return context.getString(resId);
    }

    private static int parseNonNegativeInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
