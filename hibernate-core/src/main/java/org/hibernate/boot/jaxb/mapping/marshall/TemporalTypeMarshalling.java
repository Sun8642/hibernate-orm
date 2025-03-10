/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.boot.jaxb.mapping.marshall;

import jakarta.persistence.TemporalType;

/**
 * JAXB marshalling for {@link TemporalType}
 *
 * @author Steve Ebersole
 */
public class TemporalTypeMarshalling {
	public static TemporalType fromXml(String name) {
		return TemporalType.valueOf( name );
	}

	public static String toXml(TemporalType temporalType) {
		return temporalType.name();
	}
}
