package com.isotope.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader/writer.
 *
 * <p>Isotope runs before the game's own libraries are safely usable, so it cannot lean on
 * Gson. This handles what mod metadata needs: objects become {@link LinkedHashMap} (key
 * order is kept, so a rewritten file diffs cleanly), arrays become {@link ArrayList},
 * numbers become {@link Long} or {@link Double}. Like Gson's lenient mode it tolerates
 * comments and trailing commas, because real-world mixin configs contain both.
 */
public final class Json {
	private final String s;
	private int i;

	private Json(String s) {
		this.s = s;
	}

	public static Object parse(String text) {
		Json p = new Json(text);
		// Some tools write a byte-order mark first.
		if (!text.isEmpty() && text.charAt(0) == '﻿') p.i = 1;
		Object v = p.value();
		p.ws();
		if (p.i < p.s.length()) throw p.err("trailing data");
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object v = parse(text);
		if (!(v instanceof Map)) throw new IllegalArgumentException("JSON root is not an object");
		return (Map<String, Object>) v;
	}

	private IllegalArgumentException err(String msg) {
		return new IllegalArgumentException("JSON: " + msg + " at offset " + i);
	}

	private void ws() {
		while (i < s.length()) {
			char c = s.charAt(i);

			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				i++;
			} else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
				while (i < s.length() && s.charAt(i) != '\n') i++;
			} else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
				int end = s.indexOf("*/", i + 2);
				i = end < 0 ? s.length() : end + 2;
			} else {
				break;
			}
		}
	}

	private Object value() {
		ws();
		if (i >= s.length()) throw err("unexpected end");
		char c = s.charAt(i);

		switch (c) {
		case '{': return object();
		case '[': return array();
		case '"': case '\'': return string();
		default:
			if (s.startsWith("true", i)) {
				i += 4;
				return Boolean.TRUE;
			}

			if (s.startsWith("false", i)) {
				i += 5;
				return Boolean.FALSE;
			}

			if (s.startsWith("null", i)) {
				i += 4;
				return null;
			}

			return number();
		}
	}

	private Map<String, Object> object() {
		Map<String, Object> m = new LinkedHashMap<>();
		i++;

		while (true) {
			ws();
			if (i >= s.length()) throw err("unterminated object");

			if (s.charAt(i) == '}') {
				i++;
				return m;
			}

			String key = string();
			ws();
			if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
			i++;
			m.put(key, value());
			ws();
			if (i < s.length() && s.charAt(i) == ',') i++;
		}
	}

	private List<Object> array() {
		List<Object> l = new ArrayList<>();
		i++;

		while (true) {
			ws();
			if (i >= s.length()) throw err("unterminated array");

			if (s.charAt(i) == ']') {
				i++;
				return l;
			}

			l.add(value());
			ws();
			if (i < s.length() && s.charAt(i) == ',') i++;
		}
	}

	private String string() {
		ws();
		if (i >= s.length()) throw err("expected string");
		char quote = s.charAt(i);
		if (quote != '"' && quote != '\'') throw err("expected string");
		i++;
		StringBuilder sb = new StringBuilder();

		while (i < s.length()) {
			char c = s.charAt(i++);
			if (c == quote) return sb.toString();

			if (c == '\\') {
				if (i >= s.length()) break;
				char e = s.charAt(i++);

				switch (e) {
				case 'n': sb.append('\n'); break;
				case 't': sb.append('\t'); break;
				case 'r': sb.append('\r'); break;
				case 'b': sb.append('\b'); break;
				case 'f': sb.append('\f'); break;
				case 'u':
					sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
					i += 4;
					break;
				default: sb.append(e);
				}
			} else {
				sb.append(c);
			}
		}

		throw err("unterminated string");
	}

	private Object number() {
		int start = i;

		while (i < s.length()) {
			char c = s.charAt(i);
			if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
			else break;
		}

		if (start == i) throw err("unexpected character '" + s.charAt(i) + "'");
		String n = s.substring(start, i);

		try {
			if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.parseLong(n);
			return Double.parseDouble(n);
		} catch (NumberFormatException e) {
			throw err("bad number '" + n + "'");
		}
	}

	// ---------------------------------------------------------------- writing

	public static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value, 0);
		return sb.append('\n').toString();
	}

	private static void write(StringBuilder sb, Object v, int depth) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String str) {
			quote(sb, str);
		} else if (v instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				sb.append("{}");
				return;
			}

			sb.append("{\n");
			int n = 0;

			for (Map.Entry<?, ?> e : map.entrySet()) {
				indent(sb, depth + 1);
				quote(sb, String.valueOf(e.getKey()));
				sb.append(": ");
				write(sb, e.getValue(), depth + 1);
				if (++n < map.size()) sb.append(',');
				sb.append('\n');
			}

			indent(sb, depth);
			sb.append('}');
		} else if (v instanceof List<?> list) {
			if (list.isEmpty()) {
				sb.append("[]");
				return;
			}

			sb.append("[\n");

			for (int k = 0; k < list.size(); k++) {
				indent(sb, depth + 1);
				write(sb, list.get(k), depth + 1);
				if (k + 1 < list.size()) sb.append(',');
				sb.append('\n');
			}

			indent(sb, depth);
			sb.append(']');
		} else {
			sb.append(v); // Long, Double, Boolean
		}
	}

	private static void indent(StringBuilder sb, int depth) {
		for (int k = 0; k < depth; k++) sb.append("  ");
	}

	private static void quote(StringBuilder sb, String str) {
		sb.append('"');

		for (int k = 0; k < str.length(); k++) {
			char c = str.charAt(k);

			switch (c) {
			case '"': sb.append("\\\""); break;
			case '\\': sb.append("\\\\"); break;
			case '\n': sb.append("\\n"); break;
			case '\r': sb.append("\\r"); break;
			case '\t': sb.append("\\t"); break;
			default:
				if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
				else sb.append(c);
			}
		}

		sb.append('"');
	}
}
