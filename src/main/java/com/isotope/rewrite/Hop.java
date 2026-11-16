package com.isotope.rewrite;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.isotope.model.Hierarchy;
import com.isotope.model.RedirectTable;
import com.isotope.model.RedirectTable.Member;

/**
 * One step of a rewrite, e.g. "26.1.2 to 26.2", for one mod.
 *
 * <p>A mod built for 26.1.2 and loaded on 26.3 goes through two hops. Each hop combines
 * the redirect table for that step, the class hierarchy of the hop's OLD game version,
 * and the mod's own classes, and answers one question: "the mod refers to member X on
 * class C - is there a rule for it?"
 *
 * <p>The answer needs the hierarchy because bytecode rarely names the class that
 * declares a member. A call to {@code player.getX()} is compiled as
 * {@code ServerPlayer.getX()}, while the rule lives on {@code Entity}, where
 * {@code getX} is declared. So the lookup climbs from the named class through its
 * parents until it finds a rule.
 */
public final class Hop {
	public final RedirectTable table;
	private final Hierarchy oldHierarchy;
	private final Map<String, ModClass> modClasses;
	private final Map<String, Member> methodCache = new HashMap<>();
	private final Map<String, Member> fieldCache = new HashMap<>();
	/** Cached "no rule". HashMap cannot tell a stored null from a missing key cheaply, so use a marker. */
	private static final Member NONE = new Member("", "", "", "", null, false, false);

	public Hop(RedirectTable table, Hierarchy oldHierarchy, Map<String, ModClass> modClasses) {
		this.table = table;
		this.oldHierarchy = oldHierarchy;
		this.modClasses = modClasses;
	}

	public ModClass modClass(String name) {
		return modClasses.get(name);
	}

	/** The rule that applies to {@code owner.name desc}, or null. All names are the hop's OLD names. */
	public Member resolveMethod(String owner, String name, String desc) {
		if (owner.isEmpty() || owner.charAt(0) == '[') return null; // array types only have Object's methods
		String key = owner + ' ' + name + ' ' + desc;
		Member m = methodCache.get(key);

		if (m == null) {
			m = walk(owner, name, desc, true, true, new HashSet<>());
			methodCache.put(key, m == null ? NONE : m);
		}

		return m == NONE || m == null || m.isIdentity() ? null : m;
	}

	public Member resolveField(String owner, String name, String desc) {
		if (owner.isEmpty() || owner.charAt(0) == '[') return null;
		String key = owner + ' ' + name + ' ' + desc;
		Member m = fieldCache.get(key);

		if (m == null) {
			m = walk(owner, name, desc, false, true, new HashSet<>());
			fieldCache.put(key, m == null ? NONE : m);
		}

		return m == NONE || m == null || m.isIdentity() ? null : m;
	}

	/**
	 * @param top true while still on the class the bytecode named (or a mixin's target, which
	 *            counts as the same level, because a mixin's members are merged into its target).
	 *            Private rules only apply at this level: subclasses never inherit a private member.
	 */
	private Member walk(String cls, String name, String desc, boolean method, boolean top, Set<String> seen) {
		if (cls == null || !seen.add(cls)) return null;
		Member rule = method ? table.method(cls, name, desc) : table.field(cls, name, desc);
		if (rule != null && (top || !rule.priv())) return rule;
		if (method && name.equals("<init>")) return null; // constructors are not inherited

		ModClass mod = modClasses.get(cls);

		if (mod != null) {
			for (String target : mod.mixinTargets) {
				Member r = walk(target, name, desc, method, top, seen);
				if (r != null) return r;
			}

			Member r = walk(mod.superName, name, desc, method, false, seen);
			if (r != null) return r;

			for (String i : mod.interfaces) {
				r = walk(i, name, desc, method, false, seen);
				if (r != null) return r;
			}

			return null;
		}

		Hierarchy.Node node = oldHierarchy.get(cls);
		if (node == null) return null; // a library or JDK class: no rules above it

		Member r = walk(node.superName(), name, desc, method, false, seen);
		if (r != null) return r;

		for (String i : node.interfaces()) {
			r = walk(i, name, desc, method, false, seen);
			if (r != null) return r;
		}

		return null;
	}

	/**
	 * The stub that applies to a use of {@code owner.name desc}, or null. Climbs the hierarchy like
	 * {@link #resolveMethod}, but stops at a mod class that declares the member itself: a mod's
	 * own {@code CODEC} field is a real field, not the game's removed one.
	 */
	public RedirectTable.Stub resolveStub(boolean field, String owner, String name, String desc) {
		if (!table.hasStubs() || owner.isEmpty() || owner.charAt(0) == '[') return null;
		return walkStub(field, owner, name, desc, new HashSet<>());
	}

	private RedirectTable.Stub walkStub(boolean field, String cls, String name, String desc, Set<String> seen) {
		if (cls == null || !seen.add(cls)) return null;
		RedirectTable.Stub stub = table.stub(field, cls, name, desc);
		if (stub != null) return stub;
		ModClass mod = modClasses.get(cls);

		if (mod != null) {
			if (mod.declared.contains(field ? name + ':' + desc : name + desc)) return null;
			RedirectTable.Stub r = walkStub(field, mod.superName, name, desc, seen);
			if (r != null) return r;
			for (String i : mod.interfaces) if ((r = walkStub(field, i, name, desc, seen)) != null) return r;
			return null;
		}

		Hierarchy.Node node = oldHierarchy.get(cls);
		if (node == null) return null;
		RedirectTable.Stub r = walkStub(field, node.superName(), name, desc, seen);
		if (r != null) return r;
		for (String i : node.interfaces()) if ((r = walkStub(field, i, name, desc, seen)) != null) return r;
		return null;
	}

	/** Maps every {@code Lsome/Class;} in a descriptor to this hop's new names. */
	public String mapDesc(String desc) {
		if (desc.indexOf('L') < 0) return desc;
		StringBuilder sb = new StringBuilder(desc.length() + 16);
		int i = 0;

		while (i < desc.length()) {
			char ch = desc.charAt(i);

			if (ch == 'L') {
				int semi = desc.indexOf(';', i);

				if (semi < 0) { // not a well-formed descriptor; leave the rest alone
					sb.append(desc, i, desc.length());
					break;
				}

				sb.append('L').append(table.mapClass(desc.substring(i + 1, semi))).append(';');
				i = semi + 1;
			} else {
				sb.append(ch);
				i++;
			}
		}

		return sb.toString();
	}
}
