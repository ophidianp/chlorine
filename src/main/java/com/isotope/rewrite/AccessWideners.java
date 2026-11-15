package com.isotope.rewrite;

import com.isotope.model.RedirectTable.Member;

/**
 * Remaps an access widener / class tweaker file.
 *
 * <p>These are small text files that ask the loader to make game members public or
 * non-final. Each line names a class, or a class plus a member:
 * <pre>
 * accessible method net/minecraft/world/entity/Entity getX ()D
 * mutable field net/minecraft/client/Minecraft fps I
 * </pre>
 * A line that still points at an old name would silently do nothing, and the mod would
 * then crash with an IllegalAccessError, so the names are run through the same rules.
 */
public final class AccessWideners {
	private AccessWideners() { }

	public static String remap(String text, Hop hop, int[] changes) {
		StringBuilder out = new StringBuilder(text.length() + 64);
		String[] lines = text.split("\n", -1);

		for (int n = 0; n < lines.length; n++) {
			String line = lines[n];
			String result = n == 0 ? line : remapLine(line, hop);
			if (!result.equals(line)) changes[0]++;
			out.append(result);
			if (n + 1 < lines.length) out.append('\n');
		}

		return out.toString();
	}

	private static String remapLine(String line, Hop hop) {
		String body = line;
		String comment = "";
		int hash = body.indexOf('#');

		if (hash >= 0) {
			comment = body.substring(hash);
			body = body.substring(0, hash);
		}

		String cr = body.endsWith("\r") ? "\r" : "";
		String trimmed = body.trim();
		if (trimmed.isEmpty()) return line;
		String[] p = trimmed.split("\\s+");
		int kind = -1;

		// Find the "class" / "method" / "field" keyword; what precedes it is the access directive.
		for (int i = 0; i < p.length; i++) {
			if (p[i].equals("class") || p[i].equals("method") || p[i].equals("field")) {
				kind = i;
				break;
			}
		}

		if (kind < 0) {
			// Other directives (e.g. inject-interface <class> <interface>): just map anything that is a class.
			for (int i = 1; i < p.length; i++) p[i] = hop.table.mapClass(p[i]);
		} else if (p[kind].equals("class") && p.length > kind + 1) {
			p[kind + 1] = hop.table.mapClass(p[kind + 1]);
		} else if (p.length > kind + 3) {
			String owner = p[kind + 1];
			String name = p[kind + 2];
			String desc = p[kind + 3];
			boolean method = p[kind].equals("method");
			Member rule = method ? hop.resolveMethod(owner, name, desc) : hop.resolveField(owner, name, desc);
			p[kind + 1] = hop.table.mapClass(owner);
			p[kind + 2] = rule != null ? rule.newName() : name;
			p[kind + 3] = rule != null && rule.newDesc() != null ? rule.newDesc() : hop.mapDesc(desc);
		}

		String joined = String.join("\t", p);
		// Keep untouched lines byte-identical (their original spacing included).
		if (joined.equals(String.join("\t", trimmed.split("\\s+")))) return line;
		return joined + (comment.isEmpty() ? "" : " " + comment) + cr;
	}
}
