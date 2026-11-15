package com.isotope.rewrite;

/**
 * One member reference in Mixin's string syntax, taken apart:
 * {@code [Lowner;|owner.]name[*|+|{n}][(desc)|:desc]}.
 *
 * <p>Examples: {@code tick}, {@code tick()V}, {@code Lnet/minecraft/Foo;bar(I)V},
 * {@code Lnet/minecraft/Foo;count:I}, {@code render*}.
 */
public final class Selector {
	/** Internal name of the explicit owner, or null when the reference is relative to the mixin's targets. */
	public String owner;
	/** How the owner was written, so it can be written back the same way. */
	public String ownerStyle = "";
	public String name = "";
	public String quantifier = "";
	/** Method descriptor {@code (..)..}, or a field type when {@link #fieldDesc} is set; null if absent. */
	public String desc;
	public boolean fieldDesc;

	/** Returns null for syntaxes Isotope does not understand (they are then left untouched). */
	public static Selector parse(String sel) {
		if (sel.isEmpty() || sel.contains("->") || sel.startsWith("@") || sel.indexOf(' ') >= 0) return null;
		Selector s = new Selector();
		String rest = sel;
		int paren = rest.indexOf('(');

		if (paren >= 0) {
			s.desc = rest.substring(paren);
			rest = rest.substring(0, paren);
		} else {
			int colon = rest.indexOf(':');

			if (colon >= 0) {
				s.desc = rest.substring(colon + 1);
				rest = rest.substring(0, colon);
				s.fieldDesc = true;
			}
		}

		int lastDot = rest.lastIndexOf('.');
		int semi = rest.indexOf(';');

		if (lastDot >= 0) {
			String owner = rest.substring(0, lastDot);
			rest = rest.substring(lastDot + 1);
			s.ownerStyle = owner.indexOf('/') >= 0 ? "slash." : "dot.";

			if (owner.startsWith("L") && owner.endsWith(";")) {
				owner = owner.substring(1, owner.length() - 1);
				s.ownerStyle = "L;.";
			}

			s.owner = owner.replace('.', '/');
		} else if (semi >= 0 && rest.startsWith("L")) {
			s.owner = rest.substring(1, semi);
			rest = rest.substring(semi + 1);
			s.ownerStyle = "L;";
		}

		if (rest.endsWith("*") || rest.endsWith("+")) {
			s.quantifier = rest.substring(rest.length() - 1);
			rest = rest.substring(0, rest.length() - 1);
		} else if (rest.endsWith("}") && rest.indexOf('{') >= 0) {
			s.quantifier = rest.substring(rest.indexOf('{'));
			rest = rest.substring(0, rest.indexOf('{'));
		}

		s.name = rest;
		return s;
	}

	/** True for a bare class name such as {@code net/minecraft/Foo} (used by {@code @At("NEW")}). */
	public boolean isBareClass() {
		return owner == null && desc == null && name.indexOf('/') >= 0;
	}

	public boolean isMethod() {
		return desc != null && !fieldDesc;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		if (owner != null) {
			switch (ownerStyle) {
			case "L;" -> sb.append('L').append(owner).append(';');
			case "L;." -> sb.append('L').append(owner).append(";.");
			case "slash." -> sb.append(owner).append('.');
			default -> sb.append(owner.replace('/', '.')).append('.');
			}
		}

		sb.append(name).append(quantifier);

		if (desc != null) {
			if (fieldDesc) sb.append(':');
			sb.append(desc);
		}

		return sb.toString();
	}
}
