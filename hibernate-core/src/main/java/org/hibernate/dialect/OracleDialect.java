/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.QueryTimeoutException;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.aggregate.AggregateSupport;
import org.hibernate.dialect.aggregate.OracleAggregateSupport;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.function.ModeStatsModeEmulation;
import org.hibernate.dialect.function.OracleTruncFunction;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.identity.Oracle12cIdentityColumnSupport;
import org.hibernate.dialect.pagination.LegacyOracleLimitHandler;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.Oracle12LimitHandler;
import org.hibernate.dialect.sequence.OracleSequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.dialect.temptable.TemporaryTable;
import org.hibernate.dialect.temptable.TemporaryTableKind;
import org.hibernate.dialect.unique.CreateTableUniqueDelegate;
import org.hibernate.dialect.unique.UniqueDelegate;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.config.spi.StandardConverters;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.mutation.EntityMutationTarget;
import org.hibernate.procedure.internal.StandardCallableStatementSupport;
import org.hibernate.procedure.spi.CallableStatementSupport;
import org.hibernate.query.SemanticException;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.CastType;
import org.hibernate.query.sqm.FetchClauseType;
import org.hibernate.query.sqm.IntervalType;
import org.hibernate.query.sqm.TemporalUnit;
import org.hibernate.query.sqm.mutation.internal.temptable.GlobalTemporaryTableInsertStrategy;
import org.hibernate.query.sqm.mutation.internal.temptable.GlobalTemporaryTableMutationStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableInsertStrategy;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.query.sqm.produce.function.FunctionParameterType;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.sql.model.MutationOperation;
import org.hibernate.sql.model.internal.OptionalTableUpdate;
import org.hibernate.tool.schema.extract.internal.SequenceInformationExtractorOracleDatabaseImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.JavaObjectType;
import org.hibernate.type.NullType;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.java.PrimitiveByteArrayJavaType;
import org.hibernate.type.descriptor.jdbc.AggregateJdbcType;
import org.hibernate.type.descriptor.jdbc.BlobJdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.NullJdbcType;
import org.hibernate.type.descriptor.jdbc.ObjectNullAsNullTypeJdbcType;
import org.hibernate.type.descriptor.jdbc.OracleJsonBlobJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;
import org.hibernate.type.spi.TypeConfiguration;

import jakarta.persistence.TemporalType;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.hibernate.LockOptions.NO_WAIT;
import static org.hibernate.LockOptions.SKIP_LOCKED;
import static org.hibernate.LockOptions.WAIT_FOREVER;
import static org.hibernate.cfg.AvailableSettings.BATCH_VERSIONED_DATA;
import static org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor.extractUsingTemplate;
import static org.hibernate.internal.util.StringHelper.isEmpty;
import static org.hibernate.query.sqm.TemporalUnit.DAY;
import static org.hibernate.query.sqm.TemporalUnit.HOUR;
import static org.hibernate.query.sqm.TemporalUnit.MINUTE;
import static org.hibernate.query.sqm.TemporalUnit.MONTH;
import static org.hibernate.query.sqm.TemporalUnit.SECOND;
import static org.hibernate.query.sqm.TemporalUnit.YEAR;
import static org.hibernate.type.SqlTypes.ARRAY;
import static org.hibernate.type.SqlTypes.BIGINT;
import static org.hibernate.type.SqlTypes.BINARY;
import static org.hibernate.type.SqlTypes.BOOLEAN;
import static org.hibernate.type.SqlTypes.DATE;
import static org.hibernate.type.SqlTypes.DECIMAL;
import static org.hibernate.type.SqlTypes.DOUBLE;
import static org.hibernate.type.SqlTypes.FLOAT;
import static org.hibernate.type.SqlTypes.GEOMETRY;
import static org.hibernate.type.SqlTypes.INTEGER;
import static org.hibernate.type.SqlTypes.JSON;
import static org.hibernate.type.SqlTypes.NUMERIC;
import static org.hibernate.type.SqlTypes.NVARCHAR;
import static org.hibernate.type.SqlTypes.REAL;
import static org.hibernate.type.SqlTypes.SMALLINT;
import static org.hibernate.type.SqlTypes.SQLXML;
import static org.hibernate.type.SqlTypes.STRUCT;
import static org.hibernate.type.SqlTypes.TIME;
import static org.hibernate.type.SqlTypes.TIME_WITH_TIMEZONE;
import static org.hibernate.type.SqlTypes.TINYINT;
import static org.hibernate.type.SqlTypes.VARBINARY;
import static org.hibernate.type.SqlTypes.VARCHAR;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTimestampWithNanos;

/**
 * A {@linkplain Dialect SQL dialect} for Oracle 11g Release 2 and above.
 *
 * @author Steve Ebersole
 * @author Gavin King
 * @author Loïc Lefèvre
 */
public class OracleDialect extends Dialect {

	private static final Pattern DISTINCT_KEYWORD_PATTERN = Pattern.compile( "\\bdistinct\\b", CASE_INSENSITIVE );
	private static final Pattern GROUP_BY_KEYWORD_PATTERN = Pattern.compile( "\\bgroup\\s+by\\b", CASE_INSENSITIVE );
	private static final Pattern ORDER_BY_KEYWORD_PATTERN = Pattern.compile( "\\border\\s+by\\b", CASE_INSENSITIVE );
	private static final Pattern UNION_KEYWORD_PATTERN = Pattern.compile( "\\bunion\\b", CASE_INSENSITIVE );

	private static final Pattern SQL_STATEMENT_TYPE_PATTERN =
			Pattern.compile( "^(?:/\\*.*?\\*/)?\\s*(select|insert|update|delete)\\s+.*?", CASE_INSENSITIVE );

	private static final int PARAM_LIST_SIZE_LIMIT = 1000;

	public static final String PREFER_LONG_RAW = "hibernate.dialect.oracle.prefer_long_raw";

	private static final String yqmSelect =
		"( TRUNC(%2$s, 'MONTH') + NUMTOYMINTERVAL(%1$s, 'MONTH') + ( LEAST( EXTRACT( DAY FROM %2$s ), EXTRACT( DAY FROM LAST_DAY( TRUNC(%2$s, 'MONTH') + NUMTOYMINTERVAL(%1$s, 'MONTH') ) ) ) - 1 ) )";

	private static final String ADD_YEAR_EXPRESSION = String.format( yqmSelect, "?2*12", "?3" );
	private static final String ADD_QUARTER_EXPRESSION = String.format( yqmSelect, "?2*3", "?3" );
	private static final String ADD_MONTH_EXPRESSION = String.format( yqmSelect, "?2", "?3" );

	private static final DatabaseVersion MINIMUM_VERSION = DatabaseVersion.make( 11, 2 );

	private final LimitHandler limitHandler = supportsFetchClause( FetchClauseType.ROWS_ONLY )
			? Oracle12LimitHandler.INSTANCE
			: new LegacyOracleLimitHandler( getVersion() );
	private final UniqueDelegate uniqueDelegate = new CreateTableUniqueDelegate(this);

	/** is it an Autonomous Database Cloud Service? */
	protected final boolean autonomous;

	public OracleDialect() {
		this( MINIMUM_VERSION );
	}

	public OracleDialect(DatabaseVersion version) {
		super(version);
		autonomous = false;
	}

	public OracleDialect(DialectResolutionInfo info) {
		super(info);
		autonomous = isAutonomous( info.getDatabaseMetadata() );
	}

	protected static boolean isAutonomous(DatabaseMetaData databaseMetaData) {
		if ( databaseMetaData != null ) {
			try ( java.sql.Statement s = databaseMetaData.getConnection().createStatement() ) {
				// v$pdbs is available to any user on Autonomous database
				try ( ResultSet rs = s.executeQuery( "select p.name, t.region, t.base_size, t.service, t.infrastructure\n" +
						"from v$pdbs p, JSON_TABLE(p.cloud_identity, '$' COLUMNS (region path '$.REGION', base_size number path '$.BASE_SIZE', service path '$.SERVICE', infrastructure path '$.INFRASTRUCTURE')) t" ) ) {
					return rs.next();
				}
			}
			catch ( SQLException ex ) {
				// Ignore
			}
		}
		return false;
	}

	public boolean isAutonomous() {
		return autonomous;
	}

	@Override
	protected DatabaseVersion getMinimumSupportedVersion() {
		return MINIMUM_VERSION;
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return Types.BIT;
	}

	@Override
	public void initializeFunctionRegistry(FunctionContributions functionContributions) {
		super.initializeFunctionRegistry(functionContributions);
		final TypeConfiguration typeConfiguration = functionContributions.getTypeConfiguration();

		CommonFunctionFactory functionFactory = new CommonFunctionFactory(functionContributions);
		functionFactory.ascii();
		functionFactory.char_chr();
		functionFactory.cosh();
		functionFactory.sinh();
		functionFactory.tanh();
		functionFactory.log();
		functionFactory.log10_log();
		functionFactory.soundex();
		functionFactory.trim2();
		functionFactory.initcap();
		functionFactory.instr();
		functionFactory.substr();
		functionFactory.substring_substr();
		functionFactory.leftRight_substr();
		functionFactory.translate();
		functionFactory.bitand();
		functionFactory.lastDay();
		functionFactory.toCharNumberDateTimestamp();
		functionFactory.ceiling_ceil();
		functionFactory.concat_pipeOperator();
		functionFactory.rownumRowid();
		functionFactory.sysdate();
		functionFactory.systimestamp();
		functionFactory.addMonths();
		functionFactory.monthsBetween();
		functionFactory.everyAny_minMaxCase();

		functionFactory.radians_acos();
		functionFactory.degrees_acos();

		functionFactory.median();
		functionFactory.stddev();
		functionFactory.stddevPopSamp();
		functionFactory.variance();
		functionFactory.varPopSamp();
		functionFactory.covarPopSamp();
		functionFactory.corr();
		functionFactory.regrLinearRegressionAggregates();
		functionFactory.characterLength_length( "dbms_lob.getlength(?1)" );
		// Approximate octet and bit length for clobs since exact size determination would require a custom function
		functionFactory.octetLength_pattern( "lengthb(?1)", "dbms_lob.getlength(?1)*2" );
		functionFactory.bitLength_pattern( "lengthb(?1)*8", "dbms_lob.getlength(?1)*16" );

		//Oracle has had coalesce() since 9.0.1
		functionFactory.coalesce();

		functionContributions.getFunctionRegistry().registerBinaryTernaryPattern(
				"locate",
				typeConfiguration.getBasicTypeRegistry().resolve( StandardBasicTypes.INTEGER ),
				"instr(?2,?1)",
				"instr(?2,?1,?3)",
				FunctionParameterType.STRING, FunctionParameterType.STRING, FunctionParameterType.INTEGER,
				typeConfiguration
		).setArgumentListSignature("(pattern, string[, start])");
		// The within group clause became optional in 18
		if ( getVersion().isSameOrAfter( 18 ) ) {
			functionFactory.listagg( null );
		}
		else {
			functionFactory.listagg( "within group (order by rownum)" );
		}
		functionFactory.windowFunctions();
		functionFactory.hypotheticalOrderedSetAggregates();
		functionFactory.inverseDistributionOrderedSetAggregates();
		// Oracle has a regular aggregate function named stats_mode
		functionContributions.getFunctionRegistry().register(
				"mode",
				new ModeStatsModeEmulation( typeConfiguration )
		);
		functionContributions.getFunctionRegistry().register(
				"trunc",
				new OracleTruncFunction( functionContributions.getTypeConfiguration() )
		);
		functionContributions.getFunctionRegistry().registerAlternateKey( "truncate", "trunc" );
	}

	@Override
	public int getMaxVarcharLength() {
		//with MAX_STRING_SIZE=EXTENDED, changes to 32_767
		//TODO: provide a way to change this without a custom Dialect
		return 4000;
	}

	@Override
	public int getMaxVarbinaryLength() {
		//with MAX_STRING_SIZE=EXTENDED, changes to 32_767
		return 2000;
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new OracleSqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	@Override
	public String currentDate() {
		return "current_date";
	}

	@Override
	public String currentTime() {
		return currentTimestamp();
	}

	@Override
	public String currentTimestamp() {
		return currentTimestampWithTimeZone();
	}

	@Override
	public String currentLocalTime() {
		return currentLocalTimestamp();
	}

	@Override
	public String currentLocalTimestamp() {
		return "localtimestamp";
	}

	@Override
	public String currentTimestampWithTimeZone() {
		return "current_timestamp";
	}

	@Override
	public boolean supportsInsertReturningGeneratedKeys() {
		return getVersion().isSameOrAfter( 12 );
	}

	/**
	 * Oracle doesn't have any sort of {@link Types#BOOLEAN}
	 * type or {@link Types#TIME} type, and its default behavior
	 * for casting dates and timestamps to and from strings is just awful.
	 */
	@Override
	public String castPattern(CastType from, CastType to) {
		String result;
		switch ( to ) {
			case INTEGER:
			case LONG:
				result = BooleanDecoder.toInteger( from );
				if ( result != null ) {
					return result;
				}
				break;
			case INTEGER_BOOLEAN:
				result = BooleanDecoder.toIntegerBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case YN_BOOLEAN:
				result = BooleanDecoder.toYesNoBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case BOOLEAN:
			case TF_BOOLEAN:
				result = BooleanDecoder.toTrueFalseBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case STRING:
				switch ( from ) {
					case INTEGER_BOOLEAN:
					case TF_BOOLEAN:
					case YN_BOOLEAN:
						return BooleanDecoder.toString( from );
					case DATE:
						return "to_char(?1,'YYYY-MM-DD')";
					case TIME:
						return "to_char(?1,'HH24:MI:SS')";
					case TIMESTAMP:
						return "to_char(?1,'YYYY-MM-DD HH24:MI:SS.FF9')";
					case OFFSET_TIMESTAMP:
						return "to_char(?1,'YYYY-MM-DD HH24:MI:SS.FF9TZH:TZM')";
					case ZONE_TIMESTAMP:
						return "to_char(?1,'YYYY-MM-DD HH24:MI:SS.FF9 TZR')";
				}
				break;
			case CLOB:
				// Oracle doesn't like casting to clob
				return "to_clob(?1)";
			case DATE:
				if ( from == CastType.STRING ) {
					return "to_date(?1,'YYYY-MM-DD')";
				}
				break;
			case TIME:
				if ( from == CastType.STRING ) {
					return "to_date(?1,'HH24:MI:SS')";
				}
				break;
			case TIMESTAMP:
				if ( from == CastType.STRING ) {
					return "to_timestamp(?1,'YYYY-MM-DD HH24:MI:SS.FF9')";
				}
				break;
			case OFFSET_TIMESTAMP:
				if ( from == CastType.STRING ) {
					return "to_timestamp_tz(?1,'YYYY-MM-DD HH24:MI:SS.FF9TZH:TZM')";
				}
				break;
			case ZONE_TIMESTAMP:
				if ( from == CastType.STRING ) {
					return "to_timestamp_tz(?1,'YYYY-MM-DD HH24:MI:SS.FF9 TZR')";
				}
				break;
		}
		return super.castPattern(from, to);
	}

	/**
	 * We minimize multiplicative factors by using seconds
	 * (with fractional part) as the "native" precision for
	 * duration arithmetic.
	 */
	@Override
	public long getFractionalSecondPrecisionInNanos() {
		return 1_000_000_000; //seconds
	}

	/**
	 * Oracle supports a limited list of temporal fields in the
	 * extract() function, but we can emulate some of them by
	 * using to_char() with a format string instead of extract().
	 * <p>
	 * Thus, the additional supported fields are
	 * {@link TemporalUnit#DAY_OF_YEAR},
	 * {@link TemporalUnit#DAY_OF_MONTH},
	 * {@link TemporalUnit#DAY_OF_YEAR},
	 * and {@link TemporalUnit#WEEK}.
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		switch (unit) {
			case DAY_OF_WEEK:
				return "to_number(to_char(?2,'D'))";
			case DAY_OF_MONTH:
				return "to_number(to_char(?2,'DD'))";
			case DAY_OF_YEAR:
				return "to_number(to_char(?2,'DDD'))";
			case WEEK:
				return "to_number(to_char(?2,'IW'))"; //the ISO week number
			case WEEK_OF_YEAR:
				return "to_number(to_char(?2,'WW'))";
			// Oracle doesn't support extracting the quarter
			case QUARTER:
				return "to_number(to_char(?2,'Q'))";
			// Oracle can't extract time parts from a date column, so we need to cast to timestamp
			// This is because Oracle treats date as ANSI SQL date which has no time part
			// Also see https://docs.oracle.com/cd/B28359_01/server.111/b28286/functions052.htm#SQLRF00639
			case HOUR:
				return "to_number(to_char(?2,'HH24'))";
			case MINUTE:
				return "to_number(to_char(?2,'MI'))";
			case SECOND:
				return "to_number(to_char(?2,'SS'))";
			case EPOCH:
				return "trunc((cast(?2 at time zone 'UTC' as date) - date '1970-1-1')*86400)";
			default:
				return super.extractPattern(unit);
		}
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType, IntervalType intervalType) {

		StringBuilder pattern = new StringBuilder();
		switch ( unit ) {
			case YEAR:
				pattern.append( ADD_YEAR_EXPRESSION );
				break;
			case QUARTER:
				pattern.append( ADD_QUARTER_EXPRESSION );
				break;
			case MONTH:
				pattern.append( ADD_MONTH_EXPRESSION );
				break;
			case WEEK:
				pattern.append("(?3+numtodsinterval((?2)*7,'day'))");
				break;
			case DAY:
			case HOUR:
			case MINUTE:
			case SECOND:
				pattern.append("(?3+numtodsinterval(?2,'?1'))");
				break;
			case NANOSECOND:
				pattern.append("(?3+numtodsinterval((?2)/1e9,'second'))");
				break;
			case NATIVE:
				pattern.append("(?3+numtodsinterval(?2,'second'))");
				break;
			default:
				throw new SemanticException(unit + " is not a legal field");
		}
		return pattern.toString();
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		StringBuilder pattern = new StringBuilder();
		boolean timestamp = toTemporalType == TemporalType.TIMESTAMP || fromTemporalType == TemporalType.TIMESTAMP;
		switch (unit) {
			case YEAR:
				extractField(pattern, YEAR, unit);
				break;
			case QUARTER:
			case MONTH:
				pattern.append("(");
				extractField(pattern, YEAR, unit);
				pattern.append("+");
				extractField(pattern, MONTH, unit);
				pattern.append(")");
				break;
			case WEEK:
			case DAY:
				extractField(pattern, DAY, unit);
				break;
			case HOUR:
				pattern.append("(");
				extractField(pattern, DAY, unit);
				if (timestamp) {
					pattern.append("+");
					extractField(pattern, HOUR, unit);
				}
				pattern.append(")");
				break;
			case MINUTE:
				pattern.append("(");
				extractField(pattern, DAY, unit);
				if (timestamp) {
					pattern.append("+");
					extractField(pattern, HOUR, unit);
					pattern.append("+");
					extractField(pattern, MINUTE, unit);
				}
				pattern.append(")");
				break;
			case NATIVE:
			case NANOSECOND:
			case SECOND:
				pattern.append("(");
				extractField(pattern, DAY, unit);
				if (timestamp) {
					pattern.append("+");
					extractField(pattern, HOUR, unit);
					pattern.append("+");
					extractField(pattern, MINUTE, unit);
					pattern.append("+");
					extractField(pattern, SECOND, unit);
				}
				pattern.append(")");
				break;
			default:
				throw new SemanticException("unrecognized field: " + unit);
		}
		return pattern.toString();
	}

	private void extractField(
			StringBuilder pattern,
			TemporalUnit unit, TemporalUnit toUnit) {
		pattern.append("extract(");
		pattern.append( translateExtractField(unit) );
		pattern.append(" from (?3-?2) ");
		switch (unit) {
			case YEAR:
			case MONTH:
				pattern.append("year to month");
				break;
			case DAY:
			case HOUR:
			case MINUTE:
			case SECOND:
				pattern.append("day to second");
				break;
			default:
				throw new SemanticException(unit + " is not a legal field");
		}
		pattern.append(")");
		pattern.append( unit.conversionFactor( toUnit, this ) );
	}

	@Override
	protected String columnType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			case BOOLEAN:
				// still, after all these years...
				return "number(1,0)";

			case TINYINT:
				return "number(3,0)";
			case SMALLINT:
				return "number(5,0)";
			case INTEGER:
				return "number(10,0)";
			case BIGINT:
				return "number(19,0)";
			case REAL:
				// Oracle's 'real' type is actually double precision
				return "float(24)";

			case NUMERIC:
			case DECIMAL:
				// Note that 38 is the maximum precision Oracle supports
				return "number($p,$s)";

			case DATE:
			case TIME:
				return "date";
				// the only difference between date and timestamp
				// on Oracle is that date has no fractional seconds
			case TIME_WITH_TIMEZONE:
				return "timestamp($p) with time zone";

			case VARCHAR:
				return "varchar2($l char)";
			case NVARCHAR:
				return "nvarchar2($l)";

			case BINARY:
			case VARBINARY:
				return "raw($l)";

			default:
				return super.columnType( sqlTypeCode );
		}
	}

	@Override
	protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.registerColumnTypes( typeContributions, serviceRegistry );
		final DdlTypeRegistry ddlTypeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( SQLXML, "SYS.XMLTYPE", this ) );
		ddlTypeRegistry.addDescriptor( new DdlTypeImpl( GEOMETRY, "MDSYS.SDO_GEOMETRY", this ) );
		if ( getVersion().isSameOrAfter( 21 ) ) {
			ddlTypeRegistry.addDescriptor( new DdlTypeImpl( JSON, "json", this ) );
		}
		else if ( getVersion().isSameOrAfter( 12 ) ) {
			ddlTypeRegistry.addDescriptor( new DdlTypeImpl( JSON, "blob", this ) );
		}
	}

	@Override
	public TimeZoneSupport getTimeZoneSupport() {
		return TimeZoneSupport.NATIVE;
	}

	@Override
	protected void initDefaultProperties() {
		super.initDefaultProperties();
		getDefaultProperties().setProperty( BATCH_VERSIONED_DATA,
				Boolean.toString( getVersion().isSameOrAfter( 12 ) ) );
	}

	@Override
	public int getDefaultStatementBatchSize() {
		return 15;
	}

	@Override
	public boolean getDefaultUseGetGeneratedKeys() {
		// Oracle driver reports to support getGeneratedKeys(), but they only
		// support the version taking an array of the names of the columns to
		// be returned (via its RETURNING clause).  No other driver seems to
		// support this overloaded version.
		return getVersion().isSameOrAfter( 12 );
	}

	@Override
	public JdbcType resolveSqlTypeDescriptor(
			String columnTypeName,
			int jdbcTypeCode,
			int precision,
			int scale,
			JdbcTypeRegistry jdbcTypeRegistry) {
		switch ( jdbcTypeCode ) {
			case OracleTypes.JSON:
				return jdbcTypeRegistry.getDescriptor( JSON );
			case STRUCT:
				if ( "MDSYS.SDO_GEOMETRY".equals( columnTypeName ) ) {
					jdbcTypeCode = GEOMETRY;
				}
				else {
					final AggregateJdbcType aggregateDescriptor = jdbcTypeRegistry.findAggregateDescriptor(
							// Skip the schema
							columnTypeName.substring( columnTypeName.indexOf( '.' ) + 1 )
					);
					if ( aggregateDescriptor != null ) {
						return aggregateDescriptor;
					}
				}
				break;
			case NUMERIC:
				if ( scale == -127 ) {
					// For some reason, the Oracle JDBC driver reports FLOAT
					// as NUMERIC with scale -127
					return precision <= getFloatPrecision()
							? jdbcTypeRegistry.getDescriptor( FLOAT )
							: jdbcTypeRegistry.getDescriptor( DOUBLE );
				}
				//intentional fall-through:
			case DECIMAL:
				if ( scale == 0 ) {
					// Don't infer TINYINT or SMALLINT on Oracle, since the
					// range of values of a NUMBER(3,0) or NUMBER(5,0) just
					// doesn't really match naturally.
					if ( precision <= 10 ) {
						// A NUMBER(10,0) might not fit in a 32-bit integer,
						// but it's still pretty safe to use INTEGER here,
						// since we can assume the most likely reason to find
						// a column of type NUMBER(10,0) in an Oracle database
						// is that it's intended to store an integer.
						return jdbcTypeRegistry.getDescriptor( INTEGER );
					}
					else if ( precision <= 19 ) {
						return jdbcTypeRegistry.getDescriptor( BIGINT );
					}
				}
		}
		return super.resolveSqlTypeDescriptor( columnTypeName, jdbcTypeCode, precision, scale, jdbcTypeRegistry );
	}

	/**
	 * Oracle has neither {@code BIT} nor {@code BOOLEAN}.
	 *
	 * @return false
	 */
	@Override
	public boolean supportsBitType() {
		return false;
	}

	@Override
	public String getArrayTypeName(String elementTypeName) {
		// Return null to signal that there is no array type since Oracle only has named array types
		// TODO: discuss if it makes sense to parse a config parameter to a map which we can query here
		//  e.g. `hibernate.oracle.array_types=numeric(10,0)=intarray,...`
		return null;
	}

	@Override
	public int getPreferredSqlTypeCodeForArray() {
		// Prefer to resolve to the OracleArrayJdbcType, since that will fall back to XML later if needed
		return ARRAY;
	}

	@Override
	public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.contributeTypes( typeContributions, serviceRegistry );

		typeContributions.contributeJdbcType( OracleBooleanJdbcType.INSTANCE );
		typeContributions.contributeJdbcType( OracleXmlJdbcType.INSTANCE );
		typeContributions.contributeJdbcType( OracleStructJdbcType.INSTANCE );

		if ( getVersion().isSameOrAfter( 12 ) ) {
			// account for Oracle's deprecated support for LONGVARBINARY
			// prefer BLOB, unless the user explicitly opts out
			boolean preferLong = serviceRegistry.getService( ConfigurationService.class ).getSetting(
					PREFER_LONG_RAW,
					StandardConverters.BOOLEAN,
					false
			);

			BlobJdbcType descriptor = preferLong ?
					BlobJdbcType.PRIMITIVE_ARRAY_BINDING :
					BlobJdbcType.DEFAULT;

			typeContributions.contributeJdbcType( descriptor );

			if ( getVersion().isSameOrAfter( 21 ) ) {
				typeContributions.contributeJdbcType( OracleJsonJdbcType.INSTANCE );
			}
			else {
				typeContributions.contributeJdbcType( OracleJsonBlobJdbcType.INSTANCE );
			}
		}

		typeContributions.contributeJdbcType( OracleArrayJdbcType.INSTANCE );
		// Oracle requires a custom binder for binding untyped nulls with the NULL type
		typeContributions.contributeJdbcType( NullJdbcType.INSTANCE );
		typeContributions.contributeJdbcType( ObjectNullAsNullTypeJdbcType.INSTANCE );

		// Until we remove StandardBasicTypes, we have to keep this
		typeContributions.contributeType(
				new NullType(
						NullJdbcType.INSTANCE,
						typeContributions.getTypeConfiguration()
								.getJavaTypeRegistry()
								.getDescriptor( Object.class )
				)
		);
		typeContributions.contributeType(
				new JavaObjectType(
						ObjectNullAsNullTypeJdbcType.INSTANCE,
						typeContributions.getTypeConfiguration()
								.getJavaTypeRegistry()
								.getDescriptor( Object.class )
						)
		);
	}

	@Override
	public AggregateSupport getAggregateSupport() {
		return OracleAggregateSupport.valueOf( this );
	}

	@Override
	public String getNativeIdentifierGeneratorStrategy() {
		return "sequence";
	}

	// features which change between 8i, 9i, and 10g ~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return getVersion().isBefore( 12 )
				? super.getIdentityColumnSupport()
				: new Oracle12cIdentityColumnSupport();
	}

	@Override
	public LimitHandler getLimitHandler() {
		return limitHandler;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select systimestamp from dual";
	}


	// features which remain constant across 8i, 9i, and 10g ~~~~~~~~~~~~~~~~~~

	@Override
	public String getAddColumnString() {
		return "add";
	}

	@Override
	public String getCascadeConstraintsString() {
		return " cascade constraints";
	}

	@Override
	public boolean dropConstraints() {
		return false;
	}

	@Override
	public String getAlterColumnTypeString(String columnName, String columnType, String columnDefinition) {
		return "modify " + columnName + " " + columnType;
	}

	@Override
	public boolean supportsAlterColumnType() {
		return true;
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return OracleSequenceSupport.INSTANCE;
	}

	@Override
	public String getQuerySequencesString() {
		return "select * from all_sequences";
	}

	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceInformationExtractorOracleDatabaseImpl.INSTANCE;
	}

	@Override
	public String getSelectGUIDString() {
		return "select rawtohex(sys_guid()) from dual";
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle -> {
				switch ( JdbcExceptionHelper.extractErrorCode( sqle ) ) {
					case 1:
					case 2291:
					case 2292:
						return extractUsingTemplate( "(", ")", sqle.getMessage() );
					case 1400:
						// simple nullability constraint
						return null;
					default:
						return null;
				}
			} );

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return (sqlException, message, sql) -> {
			// interpreting Oracle exceptions is much much more precise based on their specific vendor codes.
			switch ( JdbcExceptionHelper.extractErrorCode( sqlException ) ) {

				// lock timeouts ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

				case 30006:
					// ORA-30006: resource busy; acquire with WAIT timeout expired
					throw new LockTimeoutException(message, sqlException, sql);
				case 54:
					// ORA-00054: resource busy and acquire with NOWAIT specified or timeout expired
					throw new LockTimeoutException(message, sqlException, sql);
				case 4021:
					// ORA-04021 timeout occurred while waiting to lock object
					throw new LockTimeoutException(message, sqlException, sql);

				// deadlocks ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

				case 60:
					// ORA-00060: deadlock detected while waiting for resource
					return new LockAcquisitionException( message, sqlException, sql );
				case 4020:
					// ORA-04020 deadlock detected while trying to lock object
					return new LockAcquisitionException( message, sqlException, sql );

				// query cancelled ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

				case 1013:
					// ORA-01013: user requested cancel of current operation
					throw new QueryTimeoutException(  message, sqlException, sql );

				// data integrity violation ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

				case 1407:
					// ORA-01407: cannot update column to NULL
					final String constraintName = getViolatedConstraintNameExtractor().extractConstraintName( sqlException );
					return new ConstraintViolationException( message, sqlException, sql, constraintName );

				default:
					return null;
			}
		};
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, int col) throws SQLException {
		//	register the type of the out param - an Oracle specific type
		statement.registerOutParameter( col, OracleTypesHelper.INSTANCE.getOracleCursorTypeSqlType() );
		col++;
		return col;
	}

	@Override
	public ResultSet getResultSet(CallableStatement ps) throws SQLException {
		ps.execute();
		return (ResultSet) ps.getObject( 1 );
	}

	@Override
	public boolean supportsCommentOn() {
		return true;
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
	public boolean supportsExistsInSelect() {
		return false;
	}

	@Override
	public int getInExpressionCountLimit() {
		return PARAM_LIST_SIZE_LIMIT;
	}

	@Override
	public boolean forceLobAsLastValue() {
		return true;
	}

	@Override
	public boolean isEmptyStringTreatedAsNull() {
		return true;
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new GlobalTemporaryTableMutationStrategy(
				TemporaryTable.createIdTable(
						rootEntityDescriptor,
						basename -> TemporaryTable.ID_TABLE_PREFIX + basename,
						this,
						runtimeModelCreationContext
				),
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public SqmMultiTableInsertStrategy getFallbackSqmInsertStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new GlobalTemporaryTableInsertStrategy(
				TemporaryTable.createEntityTable(
						rootEntityDescriptor,
						name -> TemporaryTable.ENTITY_TABLE_PREFIX + name,
						this,
						runtimeModelCreationContext
				),
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public TemporaryTableKind getSupportedTemporaryTableKind() {
		return TemporaryTableKind.GLOBAL;
	}

	@Override
	public String getTemporaryTableCreateOptions() {
		return "on commit delete rows";
	}

	/**
	 * The {@code FOR UPDATE} clause cannot be applied when using {@code ORDER BY}, {@code DISTINCT} or views.
	 *
	 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/SELECT.html">Oracle FOR UPDATE restrictions</a>
	 */
	@Override
	public boolean useFollowOnLocking(String sql, QueryOptions queryOptions) {
		if ( isEmpty( sql ) || queryOptions == null ) {
			// ugh, used by DialectFeatureChecks (gotta be a better way)
			return true;
		}

		return DISTINCT_KEYWORD_PATTERN.matcher( sql ).find()
			|| GROUP_BY_KEYWORD_PATTERN.matcher( sql ).find()
			|| UNION_KEYWORD_PATTERN.matcher( sql ).find()
			|| ORDER_BY_KEYWORD_PATTERN.matcher( sql ).find() && queryOptions.hasLimit()
			|| queryOptions.hasLimit() && queryOptions.getLimit().getFirstRow() != null;
	}

	@Override
	public String getQueryHintString(String sql, String hints) {
		final String statementType = statementType( sql );
		final int start = sql.indexOf( statementType );
		if ( start < 0 ) {
			return sql;
		}
		else {
			int end = start + statementType.length();
			return sql.substring( 0, end ) + " /*+ " + hints + " */" + sql.substring( end );
		}
	}

	@Override
	public int getMaxAliasLength() {
		// Max identifier length is 30 for pre 12.2 versions, and 128 for 12.2+
		// but Hibernate needs to add "uniqueing info" so we account for that
		return getVersion().isSameOrAfter( 12, 2 ) ? 118 : 20;
	}

	@Override
	public int getMaxIdentifierLength() {
		// Since 12.2 version, maximum identifier length is 128
		return getVersion().isSameOrAfter( 12, 2 ) ? 128 : 30;
	}

	@Override
	public CallableStatementSupport getCallableStatementSupport() {
		// Oracle supports returning cursors
		return StandardCallableStatementSupport.REF_CURSOR_INSTANCE;
	}

	@Override
	public boolean canCreateSchema() {
		return false;
	}

	@Override
	public String getCurrentSchemaCommand() {
		return "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL";
	}

	@Override
	public boolean supportsPartitionBy() {
		return true;
	}

	private String statementType(String sql) {
		final Matcher matcher = SQL_STATEMENT_TYPE_PATTERN.matcher( sql );
		if ( matcher.matches() && matcher.groupCount() == 1 ) {
			return matcher.group(1);
		}
		else {
			throw new IllegalArgumentException( "Can't determine SQL statement type for statement: " + sql );
		}
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return false;
	}

	@Override
	public boolean supportsOffsetInSubquery() {
		return true;
	}

	@Override
	public boolean supportsFetchClause(FetchClauseType type) {
		// Until 12.2 there was a bug in the Oracle query rewriter causing ORA-00918
		// when the query contains duplicate implicit aliases in the select clause
		return getVersion().isSameOrAfter( 12, 2 );
	}

	@Override
	public boolean supportsWindowFunctions() {
		return true;
	}

	@Override
	public boolean supportsRecursiveCTE() {
		return true;
	}

	@Override
	public boolean supportsLateral() {
		return getVersion().isSameOrAfter( 12, 1 );
	}

	@Override
	public boolean supportsNoWait() {
		return true;
	}

	@Override
	public boolean supportsSkipLocked() {
		return true;
	}

	@Override
	public RowLockStrategy getWriteRowLockStrategy() {
		return RowLockStrategy.COLUMN;
	}

	@Override
	public String getForUpdateNowaitString() {
		return " for update nowait";
	}

	@Override
	public String getForUpdateString(String aliases) {
		return " for update of " + aliases;
	}

	@Override
	public String getForUpdateNowaitString(String aliases) {
		return " for update of " + aliases + " nowait";
	}

	@Override
	public String getForUpdateSkipLockedString() {
		return " for update skip locked";
	}

	@Override
	public String getForUpdateSkipLockedString(String aliases) {
		return " for update of " + aliases + " skip locked";
	}

	private String withTimeout(String lockString, int timeout) {
		switch ( timeout ) {
			case NO_WAIT:
				return supportsNoWait() ? lockString + " nowait" : lockString;
			case SKIP_LOCKED:
				return supportsSkipLocked() ? lockString + " skip locked" : lockString;
			case WAIT_FOREVER:
				return lockString;
			default:
				return supportsWait() ? lockString + " wait " + Math.round(timeout / 1e3f) : lockString;
		}
	}

	@Override
	public String getWriteLockString(int timeout) {
		return withTimeout( getForUpdateString(), timeout );
	}

	@Override
	public String getWriteLockString(String aliases, int timeout) {
		return withTimeout( getForUpdateString(aliases), timeout );
	}

	@Override
	public String getReadLockString(int timeout) {
		return getWriteLockString( timeout );
	}

	@Override
	public String getReadLockString(String aliases, int timeout) {
		return getWriteLockString( aliases, timeout );
	}

	@Override
	public boolean supportsTemporalLiteralOffset() {
		// Oracle *does* support offsets, but only
		// in the ANSI syntax, not in the JDBC
		// escape-based syntax, which we use in
		// almost all circumstances (see below)
		return false;
	}

	@Override
	public void appendDateTimeLiteral(SqlAppender appender, TemporalAccessor temporalAccessor, TemporalType precision, TimeZone jdbcTimeZone) {
		// we usually use the JDBC escape-based syntax
		// because we want to let the JDBC driver handle
		// TIME (a concept which does not exist in Oracle)
		// but for the special case of timestamps with an
		// offset we need to use the ANSI syntax
		if ( precision == TemporalType.TIMESTAMP && temporalAccessor.isSupported( ChronoField.OFFSET_SECONDS ) ) {
			appender.appendSql( "timestamp '" );
			appendAsTimestampWithNanos( appender, temporalAccessor, true, jdbcTimeZone, false );
			appender.appendSql( '\'' );
		}
		else {
			super.appendDateTimeLiteral( appender, temporalAccessor, precision, jdbcTimeZone );
		}
	}

	@Override
	public void appendDatetimeFormat(SqlAppender appender, String format) {
		// Unlike other databases, Oracle requires an explicit reset for the fm modifier,
		// otherwise all following pattern variables trim zeros
		appender.appendSql( datetimeFormat( format, true, true ).result() );
	}

	public static Replacer datetimeFormat(String format, boolean useFm, boolean resetFm) {
		String fm = useFm ? "fm" : "";
		String fmReset = resetFm ? fm : "";
		return new Replacer( format, "'", "\"" )
				//era
				.replace("GG", "AD")
				.replace("G", "AD")

				//year
				.replace("yyyy", "YYYY")
				.replace("yyy", fm + "YYYY" + fmReset)
				.replace("yy", "YY")
				.replace("y", fm + "YYYY" + fmReset)

				//month of year
				.replace("MMMM", fm + "Month" + fmReset)
				.replace("MMM", "Mon")
				.replace("MM", "MM")
				.replace("M", fm + "MM" + fmReset)

				//week of year
				.replace("ww", "IW")
				.replace("w", fm + "IW" + fmReset)
				//year for week
				.replace("YYYY", "IYYY")
				.replace("YYY", fm + "IYYY" + fmReset)
				.replace("YY", "IY")
				.replace("Y", fm + "IYYY" + fmReset)

				//week of month
				.replace("W", "W")

				//day of week
				.replace("EEEE", fm + "Day" + fmReset)
				.replace("EEE", "Dy")
				.replace("ee", "D")
				.replace("e", fm + "D" + fmReset)

				//day of month
				.replace("dd", "DD")
				.replace("d", fm + "DD" + fmReset)

				//day of year
				.replace("DDD", "DDD")
				.replace("DD", fm + "DDD" + fmReset)
				.replace("D", fm + "DDD" + fmReset)

				//am pm
				.replace("a", "AM")

				//hour
				.replace("hh", "HH12")
				.replace("HH", "HH24")
				.replace("h", fm + "HH12" + fmReset)
				.replace("H", fm + "HH24" + fmReset)

				//minute
				.replace("mm", "MI")
				.replace("m", fm + "MI" + fmReset)

				//second
				.replace("ss", "SS")
				.replace("s", fm + "SS" + fmReset)

				//fractional seconds
				.replace("SSSSSS", "FF6")
				.replace("SSSSS", "FF5")
				.replace("SSSS", "FF4")
				.replace("SSS", "FF3")
				.replace("SS", "FF2")
				.replace("S", "FF1")

				//timezones
				.replace("zzz", "TZR")
				.replace("zz", "TZR")
				.replace("z", "TZR")
				.replace("ZZZ", "TZHTZM")
				.replace("ZZ", "TZHTZM")
				.replace("Z", "TZHTZM")
				.replace("xxx", "TZH:TZM")
				.replace("xx", "TZHTZM")
				.replace("x", "TZH"); //note special case
	}

	@Override
	public void appendBinaryLiteral(SqlAppender appender, byte[] bytes) {
		appender.appendSql( "hextoraw('" );
		PrimitiveByteArrayJavaType.INSTANCE.appendString( appender, bytes );
		appender.appendSql( "')" );
	}

	@Override
	public ResultSet getResultSet(CallableStatement statement, int position) throws SQLException {
		return (ResultSet) statement.getObject( position );
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, String name) throws SQLException {
		statement.registerOutParameter( name, OracleTypesHelper.INSTANCE.getOracleCursorTypeSqlType() );
		return 1;
	}

	@Override
	public boolean supportsNamedParameters(DatabaseMetaData databaseMetaData) {
		// Not sure if it's a JDBC driver issue, but it doesn't work
		return false;
	}

	@Override
	public ResultSet getResultSet(CallableStatement statement, String name) throws SQLException {
		return (ResultSet) statement.getObject( name );
	}

	@Override
	public String generatedAs(String generatedAs) {
		return " generated always as (" + generatedAs + ")";
	}

	@Override
	public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData dbMetaData)
			throws SQLException {
		builder.setAutoQuoteInitialUnderscore(true);
		return super.buildIdentifierHelper(builder, dbMetaData);
	}

	@Override
	public boolean canDisableConstraints() {
		return true;
	}

	@Override
	public String getDisableConstraintStatement(String tableName, String name) {
		return "alter table " + tableName + " disable constraint " + name;
	}

	@Override
	public String getEnableConstraintStatement(String tableName, String name) {
		return "alter table " + tableName + " enable constraint " + name;
	}

	@Override
	public UniqueDelegate getUniqueDelegate() {
		return uniqueDelegate;
	}

	@Override
	public String getCreateUserDefinedTypeKindString() {
		return "object";
	}

	@Override
	public String rowId(String rowId) {
		return "rowid";
	}

	@Override
	public MutationOperation createOptionalTableUpdateOperation(
			EntityMutationTarget mutationTarget,
			OptionalTableUpdate optionalTableUpdate,
			SessionFactoryImplementor factory) {
		final OracleSqlAstTranslator<?> translator = new OracleSqlAstTranslator<>( factory, optionalTableUpdate );
		return translator.createMergeOperation( optionalTableUpdate );
	}
}
