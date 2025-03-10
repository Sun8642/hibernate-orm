/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.batch;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.hibernate.StaleObjectStateException;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.CockroachDialect;
import org.hibernate.dialect.OracleDialect;

import org.hibernate.testing.junit4.BaseNonConfigCoreFunctionalTestCase;
import org.junit.Test;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Version;

import static org.hibernate.testing.transaction.TransactionUtil.doInHibernate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author Vlad Mihalcea
 */
public class BatchOptimisticLockingTest extends
		BaseNonConfigCoreFunctionalTestCase {

	private final ExecutorService executorService = Executors.newSingleThreadExecutor();

	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return new Class<?>[]{
				Person.class,
		};
	}

	@Override
	protected void addSettings(Map<String,Object> settings) {
		settings.put( AvailableSettings.STATEMENT_BATCH_SIZE, String.valueOf( 2 ) );
	}

	@Test
	public void testBatchAndOptimisticLocking() {
		doInHibernate( this::sessionFactory, session -> {
			Person person1 = new Person();
			person1.id = 1L;
			person1.name = "First";
			session.persist( person1 );

			Person person2 = new Person();
			person2.id = 2L;
			person2.name = "Second";
			session.persist( person2 );

			Person person3 = new Person();
			person3.id = 3L;
			person3.name = "Third";
			session.persist( person3 );

		} );

		try {
			inTransaction( (session) -> {
				List<Person> persons = session
						.createSelectionQuery( "select p from Person p", Person.class )
						.getResultList();

				for ( int i = 0; i < persons.size(); i++ ) {
					Person person = persons.get( i );
					person.name += " Person";

					if ( i == 1 ) {
						try {
							executorService.submit( () -> {
								doInHibernate( this::sessionFactory, _session -> {
									Person _person = _session.find( Person.class, person.id );
									_person.name += " Person is the new Boss!";
								} );
							} ).get();
						}
						catch (InterruptedException|ExecutionException e) {
							fail(e.getMessage());
						}
					}
				}
			} );
		}
		catch (Exception expected) {
			assertEquals( OptimisticLockException.class, expected.getClass() );
			if ( getDialect() instanceof CockroachDialect ) {
				// CockroachDB always runs in SERIALIZABLE isolation, and uses SQL state 40001 to indicate
				// serialization failure.
				var msg = "org.hibernate.exception.LockAcquisitionException: could not execute batch";
				assertEquals(
						"org.hibernate.exception.LockAcquisitionException: could not execute batch",
						expected.getMessage().substring( 0, msg.length() )
				);
			}
			else if ( getDialect() instanceof OracleDialect && getDialect().getVersion().isBefore( 12 ) ) {
				assertTrue(
						expected.getCause() instanceof StaleObjectStateException
				);
			}
			else {
				assertEquals(
						"Batch update returned unexpected row count from update [1]; actual row count: 0; expected: 1; statement executed: update Person set name=?,version=? where id=? and version=?",
						expected.getMessage()
				);
			}
		}
	}

	@Entity(name = "Person")
	public static class Person {

		@Id
		private Long id;

		private String name;

		@Version
		private long version;
	}
}

