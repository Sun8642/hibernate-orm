/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.cte;

import org.hibernate.query.criteria.JpaCteCriteriaAttribute;
import org.hibernate.query.criteria.JpaSearchOrder;
import org.hibernate.query.sqm.NullPrecedence;
import org.hibernate.query.sqm.SortOrder;
import org.hibernate.query.sqm.tree.SqmCopyContext;

/**
 * @author Christian Beikov
 */
public class SqmSearchClauseSpecification implements JpaSearchOrder {
	private final SqmCteTableColumn cteColumn;
	private final SortOrder sortOrder;
	private NullPrecedence nullPrecedence;

	public SqmSearchClauseSpecification(SqmCteTableColumn cteColumn, SortOrder sortOrder, NullPrecedence nullPrecedence) {
		if ( cteColumn == null ) {
			throw new IllegalArgumentException( "Null cte column" );
		}
		this.cteColumn = cteColumn;
		this.sortOrder = sortOrder;
		this.nullPrecedence = nullPrecedence;
	}

	public SqmSearchClauseSpecification copy(SqmCopyContext context) {
		return new SqmSearchClauseSpecification(
				cteColumn,
				sortOrder,
				nullPrecedence
		);
	}

	public SqmCteTableColumn getCteColumn() {
		return cteColumn;
	}

	@Override
	public JpaSearchOrder nullPrecedence(NullPrecedence precedence) {
		this.nullPrecedence = precedence;
		return this;
	}

	@Override
	public boolean isAscending() {
		return sortOrder == SortOrder.ASCENDING;
	}

	@Override
	public JpaSearchOrder reverse() {
		SortOrder newSortOrder = this.sortOrder == null ? SortOrder.DESCENDING : sortOrder.reverse();
		return new SqmSearchClauseSpecification( cteColumn, newSortOrder, nullPrecedence );
	}

	@Override
	public JpaCteCriteriaAttribute getAttribute() {
		return cteColumn;
	}

	@Override
	public SortOrder getSortOrder() {
		return sortOrder;
	}

	@Override
	public NullPrecedence getNullPrecedence() {
		return nullPrecedence;
	}
}
