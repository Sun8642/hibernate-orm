/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.hibernate.PessimisticLockException;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.hint.IndexQueryHintHandler;
import org.hibernate.dialect.identity.H2FinalTableIdentityColumnSupport;
import org.hibernate.dialect.identity.H2IdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.OffsetFetchLimitHandler;
import org.hibernate.dialect.sequence.H2V1SequenceSupport;
import org.hibernate.dialect.sequence.H2V2SequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.dialect.temptable.TemporaryTable;
import org.hibernate.dialect.temptable.TemporaryTableKind;
import org.hibernate.dialect.unique.CreateTableUniqueDelegate;
import org.hibernate.dialect.unique.UniqueDelegate;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.mutation.EntityMutationTarget;
import org.hibernate.query.sqm.FetchClauseType;
import org.hibernate.query.sqm.IntervalType;
import org.hibernate.query.sqm.NullOrdering;
import org.hibernate.query.sqm.TemporalUnit;
import org.hibernate.query.sqm.mutation.internal.temptable.BeforeUseAction;
import org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableInsertStrategy;
import org.hibernate.query.sqm.mutation.internal.temptable.LocalTemporaryTableMutationStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableInsertStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.sql.model.MutationOperation;
import org.hibernate.sql.model.internal.OptionalTableUpdate;
import org.hibernate.sql.model.jdbc.OptionalTableUpdateOperation;
import org.hibernate.tool.schema.extract.internal.SequenceInformationExtractorH2DatabaseImpl;
import org.hibernate.tool.schema.extract.internal.SequenceInformationExtractorLegacyImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.descriptor.jdbc.InstantJdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.UUIDJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;
import org.hibernate.type.spi.TypeConfiguration;

import jakarta.persistence.TemporalType;

import static org.hibernate.query.sqm.TemporalUnit.SECOND;
import static org.hibernate.type.SqlTypes.ARRAY;
import static org.hibernate.type.SqlTypes.BIGINT;
import static org.hibernate.type.SqlTypes.BINARY;
import static org.hibernate.type.SqlTypes.CHAR;
import static org.hibernate.type.SqlTypes.DECIMAL;
import static org.hibernate.type.SqlTypes.DOUBLE;
import static org.hibernate.type.SqlTypes.FLOAT;
import static org.hibernate.type.SqlTypes.GEOMETRY;
import static org.hibernate.type.SqlTypes.INTERVAL_SECOND;
import static org.hibernate.type.SqlTypes.LONG32NVARCHAR;
import static org.hibernate.type.SqlTypes.LONG32VARBINARY;
import static org.hibernate.type.SqlTypes.LONG32VARCHAR;
import static org.hibernate.type.SqlTypes.NCHAR;
import static org.hibernate.type.SqlTypes.NUMERIC;
import static org.hibernate.type.SqlTypes.NVARCHAR;
import static org.hibernate.type.SqlTypes.OTHER;
import static org.hibernate.type.SqlTypes.TIMESTAMP_UTC;
import static org.hibernate.type.SqlTypes.UUID;
import static org.hibernate.type.SqlTypes.VARBINARY;
import static org.hibernate.type.SqlTypes.VARCHAR;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsDate;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsLocalTime;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTime;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTimestampWithMillis;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTimestampWithNanos;

/**
 * A {@linkplain Dialect SQL dialect} for H2.
 *
 * @author Thomas Mueller
 */
public class H2Dialect extends Dialect {
	private static final DatabaseVersion MINIMUM_VERSION = DatabaseVersion.make( 1, 4, 197 );

	private final boolean ansiSequence;
	private final boolean cascadeConstraints;
	private final boolean useLocalTime;

	private final SequenceInformationExtractor sequenceInformationExtractor;
	private final String querySequenceString;
	private final UniqueDelegate uniqueDelegate = new CreateTableUniqueDelegate(this);

	private final OptionalTableUpdateStrategy optionalTableUpdateStrategy;

	public H2Dialect(DialectResolutionInfo info) {
		this( parseVersion( info ) );
		registerKeywords( info );
	}

	public H2Dialect() {
		this( MINIMUM_VERSION );
	}

	public H2Dialect(DatabaseVersion version) {
		super(version);

//		supportsTuplesInSubqueries = version.isSameOrAfter( 1, 4, 198 );

		// Prior to 1.4.200 there was no support for 'current value for sequence_name'
		// After 2.0.202 there is no support for 'sequence_name.nextval' and 'sequence_name.currval'
		ansiSequence = version.isSameOrAfter( 1, 4, 200 );

		// Prior to 1.4.200 the 'cascade' in 'drop table' was implicit
		cascadeConstraints = version.isSameOrAfter( 1, 4, 200 );
		// 1.4.200 introduced changes in current_time and current_timestamp
		useLocalTime = version.isSameOrAfter( 1, 4, 200 );

		this.sequenceInformationExtractor = version.isSameOrAfter( 1, 4, 201 )
				? SequenceInformationExtractorLegacyImpl.INSTANCE
				: SequenceInformationExtractorH2DatabaseImpl.INSTANCE;
		this.querySequenceString = "select * from INFORMATION_SCHEMA.SEQUENCES";

		this.optionalTableUpdateStrategy = version.isSameOrAfter( 1, 4, 200 )
				? H2Dialect::usingMerge
				: H2Dialect::withoutMerge;
	}

	private static DatabaseVersion parseVersion(DialectResolutionInfo info) {
		return DatabaseVersion.make( info.getMajor(), info.getMinor(), parseBuildId( info ) );
	}

	private static int parseBuildId(DialectResolutionInfo info) {
		final String databaseVersion = info.getDatabaseVersion();
		if ( databaseVersion == null ) {
			return 0;
		}

		final String[] bits = databaseVersion.split("[. ]");
		return bits.length > 2 ? Integer.parseInt( bits[2] ) : 0;
	}

	@Override
	public boolean getDefaultNonContextualLobCreation() {
		// http://code.google.com/p/h2database/issues/detail?id=235
		return true;
	}

	@Override
	public boolean supportsStandardArrays() {
		return getVersion().isSameOrAfter( 2 );
	}

	@Override
	protected String columnType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			// prior to version 2.0, H2 reported NUMERIC columns as DECIMAL,
			// which caused problems for schema update tool
			case NUMERIC:
				return getVersion().isBefore( 2 ) ? columnType( DECIMAL ) : super.columnType( sqlTypeCode );
			case NCHAR:
				return columnType( CHAR );
			case NVARCHAR:
				return columnType( VARCHAR );
			default:
				return super.columnType( sqlTypeCode );
		}
	}

	@Override
	protected String castType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			case CHAR:
			case NCHAR:
				return "char";
			case VARCHAR:
			case NVARCHAR:
			case LONG32VARCHAR:
			case LONG32NVARCHAR:
				return "varchar";
			case BINARY:
			case VARBINARY:
			case LONG32VARBINARY:
				return "varbinary";
		}
		return super.castType( sqlTypeCode );
	}

	@Override
	protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.registerColumnTypes( typeContributions, serviceRegistry );
		final DdlTypeRegistry ddlTypeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

		if ( getVersion().isBefore( 2 ) ) {
			ddlTypeRegistry.addDescriptor( new DdlTypeImpl( ARRAY, "array", this ) );
		}
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( UUID, "uuid", this ) );
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( GEOMETRY, "geometry", this ) );
		if ( getVersion().isSameOrAfter( 1, 4, 198 ) ) {
			ddlTypeRegistry.addDescriptor( new DdlTypeImpl( INTERVAL_SECOND, "interval second($p,$s)", this ) );
		}
	}

	@Override
	public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.contributeTypes( typeContributions, serviceRegistry );

		final JdbcTypeRegistry jdbcTypeRegistry = typeContributions.getTypeConfiguration()
				.getJdbcTypeRegistry();

		jdbcTypeRegistry.addDescriptor( TIMESTAMP_UTC, InstantJdbcType.INSTANCE );
		jdbcTypeRegistry.addDescriptorIfAbsent( UUIDJdbcType.INSTANCE );
		if ( getVersion().isSameOrAfter( 1, 4, 198 ) ) {
			jdbcTypeRegistry.addDescriptorIfAbsent( H2DurationIntervalSecondJdbcType.INSTANCE );
		}
	}

	@Override
	public int getDefaultStatementBatchSize() {
		return 15;
	}

	public boolean hasOddDstBehavior() {
		// H2 1.4.200 has a bug: https://github.com/h2database/h2database/issues/3184
		return getVersion().isSameOrAfter( 1, 4, 200 );
	}

	@Override
	public void initializeFunctionRegistry(FunctionContributions functionContributions) {
		super.initializeFunctionRegistry(functionContributions);

		CommonFunctionFactory functionFactory = new CommonFunctionFactory(functionContributions);

		// H2 needs an actual argument type for aggregates like SUM, AVG, MIN, MAX to determine the result type
		functionFactory.aggregates( this, SqlAstNodeRenderingMode.NO_PLAIN_PARAMETER );
		// AVG by default uses the input type, so we possibly need to cast the argument type, hence a special function
		functionFactory.avg_castingNonDoubleArguments( this, SqlAstNodeRenderingMode.NO_PLAIN_PARAMETER );

		functionFactory.pi();
		functionFactory.cot();
		functionFactory.radians();
		functionFactory.degrees();
		functionFactory.log10();
		functionFactory.mod_operator();
		functionFactory.rand();
		functionFactory.soundex();
		functionFactory.translate();
		functionFactory.bitand();
		functionFactory.bitor();
		functionFactory.bitxor();
		functionFactory.bitAndOr();
		functionFactory.yearMonthDay();
		functionFactory.hourMinuteSecond();
		functionFactory.dayOfWeekMonthYear();
		functionFactory.weekQuarter();
		functionFactory.daynameMonthname();
		if ( useLocalTime ) {
			functionFactory.localtimeLocaltimestamp();
		}
		functionFactory.trunc_dateTrunc();
		functionFactory.dateTrunc();
		functionFactory.bitLength();
		functionFactory.octetLength();
		functionFactory.ascii();
		functionFactory.octetLength();
		functionFactory.space();
		functionFactory.repeat();
		functionFactory.chr_char();
		functionFactory.instr();
		functionFactory.substr();
		//also natively supports ANSI-style substring()
		functionFactory.position();
		functionFactory.trim1();
		functionFactory.concat_pipeOperator();
		functionFactory.nowCurdateCurtime();
		functionFactory.sysdate();
		functionFactory.insert();
//		functionFactory.everyAny(); //this would work too
		functionFactory.everyAny_boolAndOr();
		functionFactory.median();
		functionFactory.stddevPopSamp();
		functionFactory.varPopSamp();
		if ( getVersion().isSame( 1, 4, 200 ) ) {
			// See https://github.com/h2database/h2database/issues/2518
			functionFactory.format_toChar();
		}
		else {
			functionFactory.format_formatdatetime();
		}
		functionFactory.rownum();
		if ( getVersion().isSameOrAfter( 1, 4, 200 ) ) {
			functionFactory.windowFunctions();
			if ( getVersion().isSameOrAfter( 2 ) ) {
				functionFactory.listagg( null );
				functionFactory.inverseDistributionOrderedSetAggregates();
				functionFactory.hypotheticalOrderedSetAggregates();
			}
			else {
				// Use group_concat until 2.x as listagg was buggy
				functionFactory.listagg_groupConcat();
			}
		}
		else {
			functionFactory.listagg_groupConcat();
		}
	}

	@Override
	public void augmentPhysicalTableTypes(List<String> tableTypesList) {
		if ( getVersion().isSameOrAfter( 2 ) ) {
			tableTypesList.add( "BASE TABLE" );
		}
	}

	@Override
	protected Integer resolveSqlTypeCode(String columnTypeName, TypeConfiguration typeConfiguration) {
		switch ( columnTypeName ) {
			case "FLOAT(24)":
				// Use REAL instead of FLOAT to get Float as recommended Java type
				return Types.REAL;
		}
		return super.resolveSqlTypeCode( columnTypeName, typeConfiguration );
	}

	@Override
	public JdbcType resolveSqlTypeDescriptor(
			String columnTypeName,
			int jdbcTypeCode,
			int precision,
			int scale,
			JdbcTypeRegistry jdbcTypeRegistry) {
		// As of H2 2.0 we get a FLOAT type code even though it is a DOUBLE
		switch ( jdbcTypeCode ) {
			case FLOAT:
				if ( "DOUBLE PRECISION".equals( columnTypeName ) ) {
					return jdbcTypeRegistry.getDescriptor( DOUBLE );
				}
				break;
			case OTHER:
				if ( "GEOMETRY".equals( columnTypeName ) ) {
					return jdbcTypeRegistry.getDescriptor( GEOMETRY );
				}
				break;
		}
		return super.resolveSqlTypeDescriptor( columnTypeName, jdbcTypeCode, precision, scale, jdbcTypeRegistry );
	}

	@Override
	protected Integer resolveSqlTypeCode(String typeName, String baseTypeName, TypeConfiguration typeConfiguration) {
		switch ( baseTypeName ) {
			case "CHARACTER VARYING":
				return VARCHAR;
		}
		return super.resolveSqlTypeCode( typeName, baseTypeName, typeConfiguration );
	}

	@Override
	public int getMaxVarcharLength() {
		return 1_048_576;
	}

	@Override
	public String currentTime() {
		return useLocalTime ? "localtime" : super.currentTime();
	}

	@Override
	public String currentTimestamp() {
		return useLocalTime ? "localtimestamp" : super.currentTimestamp();
	}

	@Override
	public String currentTimestampWithTimeZone() {
		return "current_timestamp";
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new H2SqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	/**
	 * In H2, the extract() function does not return
	 * fractional seconds for the field
	 * {@link TemporalUnit#SECOND}. We work around
	 * this here with two calls to extract().
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		return unit == SECOND
				? "(" + super.extractPattern(unit) + "+extract(nanosecond from ?2)/1e9)"
				: super.extractPattern(unit);
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType, IntervalType intervalType) {
		if ( intervalType != null ) {
			return "(?2+?3)";
		}
		return "dateadd(?1,?2,?3)";
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		if ( unit == null ) {
			return "(?3-?2)";
		}
		return "datediff(?1,?2,?3)";
	}

	@Override
	public void appendDateTimeLiteral(
			SqlAppender appender,
			TemporalAccessor temporalAccessor,
			TemporalType precision,
			TimeZone jdbcTimeZone) {
		switch ( precision ) {
			case DATE:
				appender.appendSql( "date '" );
				appendAsDate( appender, temporalAccessor );
				appender.appendSql( '\'' );
				break;
			case TIME:
				if ( supportsTimeLiteralOffset() && temporalAccessor.isSupported( ChronoField.OFFSET_SECONDS )  ) {
					appender.appendSql( "time with time zone '" );
					appendAsTime( appender, temporalAccessor, supportsTemporalLiteralOffset(), jdbcTimeZone );
				}
				else {
					appender.appendSql( "time '" );
					appendAsLocalTime( appender, temporalAccessor );
				}
				appender.appendSql( '\'' );
				break;
			case TIMESTAMP:
				if ( supportsTemporalLiteralOffset() && temporalAccessor.isSupported( ChronoField.OFFSET_SECONDS ) ) {
					appender.appendSql( "timestamp with time zone '" );
					appendAsTimestampWithNanos( appender, temporalAccessor, true, jdbcTimeZone );
					appender.appendSql( '\'' );
				}
				else {
					appender.appendSql( "timestamp '" );
					appendAsTimestampWithNanos( appender, temporalAccessor, false, jdbcTimeZone );
					appender.appendSql( '\'' );
				}
				break;
			default:
				throw new IllegalArgumentException();
		}
	}

	@Override
	public void appendDateTimeLiteral(SqlAppender appender, Date date, TemporalType precision, TimeZone jdbcTimeZone) {
		switch ( precision ) {
			case DATE:
				appender.appendSql( "date '" );
				appendAsDate( appender, date );
				appender.appendSql( '\'' );
				break;
			case TIME:
				if ( supportsTimeLiteralOffset() ) {
					appender.appendSql( "time with time zone '" );
					appendAsTime( appender, date, jdbcTimeZone );
				}
				else {
					appender.appendSql( "time '" );
					appendAsLocalTime( appender, date );
				}
				appender.appendSql( '\'' );
				break;
			case TIMESTAMP:
				appender.appendSql( "timestamp with time zone '" );
				appendAsTimestampWithNanos( appender, date, jdbcTimeZone );
				appender.appendSql( '\'' );
				break;
			default:
				throw new IllegalArgumentException();
		}
	}

	@Override
	public void appendDateTimeLiteral(
			SqlAppender appender,
			Calendar calendar,
			TemporalType precision,
			TimeZone jdbcTimeZone) {
		switch ( precision ) {
			case DATE:
				appender.appendSql( "date '" );
				appendAsDate( appender, calendar );
				appender.appendSql( '\'' );
				break;
			case TIME:
				if ( supportsTimeLiteralOffset() ) {
					appender.appendSql( "time with time zone '" );
					appendAsTime( appender, calendar, jdbcTimeZone );
				}
				else {
					appender.appendSql( "time '" );
					appendAsLocalTime( appender, calendar );
				}
				appender.appendSql( '\'' );
				break;
			case TIMESTAMP:
				appender.appendSql( "timestamp with time zone '" );
				appendAsTimestampWithMillis( appender, calendar, jdbcTimeZone );
				appender.appendSql( '\'' );
				break;
			default:
				throw new IllegalArgumentException();
		}
	}

	public boolean supportsTimeLiteralOffset() {
		return getVersion().isSameOrAfter( 1, 4, 200 );
	}

	@Override
	public boolean supportsTemporalLiteralOffset() {
		return true;
	}

	@Override
	public TimeZoneSupport getTimeZoneSupport() {
		return TimeZoneSupport.NATIVE;
	}

	@Override
	public void appendBooleanValueString(SqlAppender appender, boolean bool) {
		appender.appendSql( bool );
	}

	@Override
	public LimitHandler getLimitHandler() {
		return OffsetFetchLimitHandler.INSTANCE;
	}

	@Override
	public boolean supportsDistinctFromPredicate() {
		return true;
	}

	@Override
	public boolean supportsIfExistsAfterTableName() {
		return !supportsIfExistsBeforeTableName();
	}

	@Override
	public boolean supportsIfExistsBeforeTableName() {
		return cascadeConstraints;
	}

	@Override
	public boolean supportsIfExistsAfterAlterTable() {
		return cascadeConstraints;
	}

	@Override
	public boolean supportsIfExistsBeforeConstraintName() {
		return true;
	}

	@Override
	public String getCascadeConstraintsString() {
		return cascadeConstraints ? " cascade "
				: super.getCascadeConstraintsString();
	}

	@Override
	public boolean supportsAlterColumnType() {
		return true;
	}

	@Override
	public String getAlterColumnTypeString(String columnName, String columnType, String columnDefinition) {
		return "alter column " + columnName + " set data type " + columnType;
		// if only altering the type, no need to specify the whole definition
//		return "alter column " + columnName + " " + columnDefinition;
	}

	@Override
	public boolean supportsCommentOn() {
		return true;
	}

	@Override
	public boolean dropConstraints() {
		return false;
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return ansiSequence ? H2V2SequenceSupport.INSTANCE: H2V1SequenceSupport.INSTANCE;
	}

	@Override
	public String getQuerySequencesString() {
		return querySequenceString;
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return sequenceInformationExtractor;
	}

	@Override
	public NullOrdering getNullOrdering() {
		return NullOrdering.SMALLEST;
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType entityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new LocalTemporaryTableMutationStrategy(
				TemporaryTable.createIdTable(
						entityDescriptor,
						basename -> TemporaryTable.ID_TABLE_PREFIX + basename,
						this,
						runtimeModelCreationContext
				),
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public SqmMultiTableInsertStrategy getFallbackSqmInsertStrategy(
			EntityMappingType entityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new LocalTemporaryTableInsertStrategy(
				TemporaryTable.createEntityTable(
						entityDescriptor,
						name -> TemporaryTable.ENTITY_TABLE_PREFIX + name,
						this,
						runtimeModelCreationContext
				),
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public TemporaryTableKind getSupportedTemporaryTableKind() {
		return TemporaryTableKind.LOCAL;
	}

	@Override
	public BeforeUseAction getTemporaryTableBeforeUseAction() {
		return BeforeUseAction.CREATE;
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle -> {
				// 23000: Check constraint violation: {0}
				// 23001: Unique index or primary key violation: {0}
				if ( sqle.getSQLState().startsWith( "23" ) ) {
					final String message = sqle.getMessage();
					final int idx = message.indexOf( "violation: " );
					if ( idx > 0 ) {
						String constraintName = message.substring( idx + "violation: ".length() );
						if ( sqle.getSQLState().equals( "23506" ) ) {
							constraintName = constraintName.substring( 1, constraintName.indexOf( ":" ) );
						}
						return constraintName;
					}
				}
				return null;
			} );

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return (sqlException, message, sql) -> {
			final int errorCode = JdbcExceptionHelper.extractErrorCode( sqlException );

			switch (errorCode) {
				case 40001:
					// DEADLOCK DETECTED
					return new LockAcquisitionException(message, sqlException, sql);
				case 50200:
					// LOCK NOT AVAILABLE
					return new PessimisticLockException(message, sqlException, sql);
				case 90006:
					// NULL not allowed for column [90006-145]
					final String constraintName = getViolatedConstraintNameExtractor().extractConstraintName(sqlException);
					return new ConstraintViolationException(message, sqlException, sql, constraintName);
			}

			return null;
		};
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "call current_timestamp()";
	}


	// Overridden informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsLobValueChangePropagation() {
		return false;
	}

	@Override
	public boolean supportsTupleCounts() {
		return true;
	}

	@Override
	public boolean requiresParensForTupleDistinctCounts() {
		return true;
	}

	@Override
	public boolean doesReadCommittedCauseWritersToBlockReaders() {
		// see http://groups.google.com/group/h2-database/browse_thread/thread/562d8a49e2dabe99?hl=en
		return true;
	}

	@Override
	public SelectItemReferenceStrategy getGroupBySelectItemReferenceStrategy() {
		return SelectItemReferenceStrategy.ALIAS;
	}

	@Override
	public boolean supportsOffsetInSubquery() {
		return true;
	}

	@Override
	public boolean supportsWindowFunctions() {
		return getVersion().isSameOrAfter( 1, 4, 200 );
	}

	@Override
	public boolean supportsRecursiveCTE() {
		return getVersion().isSameOrAfter( 1, 4, 196 );
	}

	@Override
	public boolean supportsFetchClause(FetchClauseType type) {
		return getVersion().isSameOrAfter( 1, 4, 198 );
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return getVersion().isSameOrAfter( 2 )
				? H2FinalTableIdentityColumnSupport.INSTANCE
				: H2IdentityColumnSupport.INSTANCE;
	}

	/**
	 * @return {@code true} because we can use {@code select ... from final table (insert .... )}
	 */
	@Override
	public boolean supportsInsertReturning() {
		return getVersion().isSameOrAfter( 2 );
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, int position) throws SQLException {
		return position;
	}

	@Override
	public String getQueryHintString(String query, String hints) {
		return IndexQueryHintHandler.INSTANCE.addQueryHints( query, hints );
	}

	@Override
	public void appendDatetimeFormat(SqlAppender appender, String format) {
		if ( getVersion().isSame( 1, 4, 200 ) ) {
			// See https://github.com/h2database/h2database/issues/2518
			appender.appendSql( OracleDialect.datetimeFormat( format, true, true ).result() );
		}
		else {
			appender.appendSql(
					new Replacer( format, "'", "''" )
					.replace("e", "u")
					.replace( "xxx", "XXX" )
					.replace( "xx", "XX" )
					.replace( "x", "X" )
					.result()
			);
		}
	}

	public String translateExtractField(TemporalUnit unit) {
		switch ( unit ) {
			case DAY_OF_MONTH: return "day";
			case WEEK: return "iso_week";
			default: return unit.toString();
		}
	}

	@Override
	public String generatedAs(String generatedAs) {
		return " generated always as (" + generatedAs + ")";
	}

	@Override
	public boolean canDisableConstraints() {
		return true;
	}

	@Override
	public String getEnableConstraintsStatement() {
		return "set referential_integrity true";
	}

	@Override
	public String getDisableConstraintsStatement() {
		return "set referential_integrity false";
	}

	@Override
	public UniqueDelegate getUniqueDelegate() {
		return uniqueDelegate;
	}

	@Override
	public String rowId(String rowId) {
		return "_rowid_";
	}

	@Override
	public int rowIdSqlType() {
		return BIGINT;
	}

	@FunctionalInterface
	private interface OptionalTableUpdateStrategy {
		MutationOperation buildMutationOperation(
				EntityMutationTarget mutationTarget,
				OptionalTableUpdate optionalTableUpdate,
				SessionFactoryImplementor factory);
	}

	@Override
	public MutationOperation createOptionalTableUpdateOperation(
			EntityMutationTarget mutationTarget,
			OptionalTableUpdate optionalTableUpdate,
			SessionFactoryImplementor factory) {
		return optionalTableUpdateStrategy.buildMutationOperation( mutationTarget, optionalTableUpdate, factory );
	}

	private static MutationOperation usingMerge(
			EntityMutationTarget mutationTarget,
			OptionalTableUpdate optionalTableUpdate,
			SessionFactoryImplementor factory) {
		final H2SqlAstTranslator<?> translator = new H2SqlAstTranslator<>( factory, optionalTableUpdate );
		return translator.createMergeOperation( optionalTableUpdate );
	}

	private static MutationOperation withoutMerge(
			EntityMutationTarget mutationTarget,
			OptionalTableUpdate optionalTableUpdate,
			SessionFactoryImplementor factory) {
		return new OptionalTableUpdateOperation( mutationTarget, optionalTableUpdate, factory );
	}
}
