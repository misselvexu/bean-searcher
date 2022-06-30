package com.ejlchina.searcher.implement;

import com.ejlchina.searcher.*;
import com.ejlchina.searcher.dialect.Dialect;
import com.ejlchina.searcher.group.Group;
import com.ejlchina.searcher.param.FetchType;
import com.ejlchina.searcher.param.FieldParam;
import com.ejlchina.searcher.param.OrderBy;
import com.ejlchina.searcher.param.Paging;
import com.ejlchina.searcher.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认 SQL 解析器
 * 
 * @author Troy.Zhou @ 2017-03-20
 * @since v1.1.1
 */
public class DefaultSqlResolver extends DialectWrapper implements SqlResolver {


	public DefaultSqlResolver() {
	}

	public DefaultSqlResolver(Dialect dialect) {
		super(dialect);
	}

	@Override
	public <T> SearchSql<T> resolve(BeanMeta<T> beanMeta, SearchParam searchParam) {
		List<String> fetchFields = searchParam.getFetchFields();
		FetchType fetchType = searchParam.getFetchType();

		SearchSql<T> searchSql = new SearchSql<>(beanMeta, fetchFields);
		searchSql.setShouldQueryCluster(fetchType.shouldQueryCluster());
		searchSql.setShouldQueryList(fetchType.shouldQueryList());

		if (fetchType.shouldQueryTotal()) {
			searchSql.setCountAlias(getCountAlias(beanMeta));
		}
		String[] summaryFields = fetchType.getSummaryFields();
		for (String summaryField : summaryFields) {
			FieldMeta fieldMeta = beanMeta.getFieldMeta(summaryField);
			if (fieldMeta == null) {
				throw new SearchException("求和属性【" + summaryField + "】没有和数据库字段做映射，请检查该属性是否 已被忽略 或 是否已被 @DbField 正确注解！");
			}
			searchSql.addSummaryAlias(getSummaryAlias(fieldMeta));
		}
		Map<String, Object> paraMap = searchParam.getParaMap();

		SqlWrapper<Object> fieldSelectSqlWrapper = buildFieldSelectSql(beanMeta, fetchFields, paraMap);
		SqlWrapper<Object> fromWhereSqlWrapper = buildFromWhereSql(beanMeta, searchParam.getParamsGroup(), paraMap);
		String fieldSelectSql = fieldSelectSqlWrapper.getSql();
		String fromWhereSql = fromWhereSqlWrapper.getSql();

		if (fetchType.shouldQueryTotal() || summaryFields.length > 0) {
			List<String> summaryAliases = searchSql.getSummaryAliases();
			String countAlias = searchSql.getCountAlias();
			SqlWrapper<Object> clusterSelectSql = buildClusterSelectSql(beanMeta, summaryFields, summaryAliases, countAlias, paraMap);
			String clusterSql = buildClusterSql(beanMeta, clusterSelectSql.getSql(), fieldSelectSql, fromWhereSql);
			searchSql.setClusterSqlString(clusterSql);
			searchSql.addClusterSqlParams(clusterSelectSql.getParas());
			// 只有在 DistinctOrGroupBy 条件下，聚族查询 SQL 里才会出现 字段查询 语句，才需要将 字段内嵌参数放到 聚族参数里
			if (beanMeta.isDistinctOrGroupBy()) {
				searchSql.addClusterSqlParams(fieldSelectSqlWrapper.getParas());
			}
			searchSql.addClusterSqlParams(fromWhereSqlWrapper.getParas());
		}
		if (fetchType.shouldQueryList()) {
			List<OrderBy> orderBys = searchParam.getOrderBys();
			Paging paging = searchParam.getPaging();
			SqlWrapper<Object> listSql = buildListSql(beanMeta, fieldSelectSql, fromWhereSql, orderBys, paging, fetchFields, paraMap);
			searchSql.setListSqlString(listSql.getSql());
			searchSql.addListSqlParams(fieldSelectSqlWrapper.getParas());
			searchSql.addListSqlParams(fromWhereSqlWrapper.getParas());
			searchSql.addListSqlParams(listSql.getParas());
		}
		return searchSql;
	}

	protected <T> SqlWrapper<Object> buildFieldSelectSql(BeanMeta<T> beanMeta, List<String> fetchFields, Map<String, Object> paraMap) {
		SqlWrapper<Object> sqlWrapper = new SqlWrapper<>();
		StringBuilder builder = new StringBuilder("select ");
		if (beanMeta.isDistinct()) {
			builder.append("distinct ");
		}
		int fieldCount = fetchFields.size();
		for (int i = 0; i < fieldCount; i++) {
			String field = fetchFields.get(i);
			FieldMeta meta = beanMeta.requireFieldMeta(field);
			SqlWrapper<Object> dbFieldSql = resolveDbFieldSql(meta.getFieldSql(), paraMap);
			builder.append(dbFieldSql.getSql()).append(" ").append(meta.getDbAlias());
			if (i < fieldCount - 1) {
				builder.append(", ");
			}
			sqlWrapper.addParas(dbFieldSql.getParas());
		}
		sqlWrapper.setSql(builder.toString());
		return sqlWrapper;
	}

	protected <T> SqlWrapper<Object> buildClusterSelectSql(BeanMeta<T> beanMeta, String[] summaryFields, List<String> summaryAliases, String countAlias, Map<String, Object> paraMap) {
		StringBuilder builder = new StringBuilder("select ");
		if (countAlias != null) {
			builder.append("count(*) ").append(countAlias);
			if (summaryFields.length > 0) {
				builder.append(", ");
			}
		}
		SqlWrapper<Object> sqlWrapper = new SqlWrapper<>();
		boolean distinctOrGroupBy = beanMeta.isDistinctOrGroupBy();
		for (int i = 0; i < summaryFields.length; i++) {
			FieldMeta fieldMeta = beanMeta.requireFieldMeta(summaryFields[i]);
			builder.append("sum(");
			if (distinctOrGroupBy) {
				builder.append(fieldMeta.getDbAlias());
			} else {
				SqlWrapper<Object> fieldSql = resolveDbFieldSql(fieldMeta.getFieldSql(), paraMap);
				builder.append(fieldSql.getSql());
				sqlWrapper.addParas(fieldSql.getParas());
			}
			builder.append(") ").append(summaryAliases.get(i));
			if (i < summaryFields.length - 1) {
				builder.append(", ");
			}
		}
		sqlWrapper.setSql(builder.toString());
		return sqlWrapper;
	}

	protected <T> SqlWrapper<Object> buildFromWhereSql(BeanMeta<T> beanMeta, Group<List<FieldParam>> paramsGroup, Map<String, Object> paraMap) {
		SqlWrapper<Object> sqlWrapper = new SqlWrapper<>();
		SqlWrapper<Object> tableSql = resolveTableSql(beanMeta.getTableSnippet(), paraMap);
		sqlWrapper.addParas(tableSql.getParas());
		StringBuilder builder = new StringBuilder(" from ").append(tableSql.getSql());

		String where = beanMeta.getWhere();
		if (StringUtils.isNotBlank(where)) {
			where = buildCondition(sqlWrapper, beanMeta.getWhereSqlParas(), paraMap, where);
		}
		boolean hasWhere = StringUtils.isNotBlank(where);

		SqlWrapper<Object> groupBy = resolveGroupBy(beanMeta, paraMap);

		GroupPair groupPair = resolveGroupPair(paramsGroup, groupBy);
		Group<List<FieldParam>> whereGroup = groupPair.getWhereGroup();

		boolean hasWhereParams = whereGroup != null && whereGroup.judgeAny(l -> l.size() > 0);

		if (hasWhere || hasWhereParams) {
			builder.append(" where ");
			if (hasWhere) {
				builder.append("(").append(where).append(")");
				if (hasWhereParams) {
					builder.append(" and ");
				}
			}
		}
		if (whereGroup != null) {
			sqlWrapper.addParas(buildWithParamGroup(beanMeta, builder, whereGroup, paraMap));
		}
		if (groupBy != null) {
			builder.append(" group by ").append(groupBy.getSql());
			sqlWrapper.addParas(groupBy.getParas());
			String having = beanMeta.getHaving();
			if (StringUtils.isNotBlank(having)) {
				having = buildCondition(sqlWrapper, beanMeta.getHavingSqlParas(), paraMap, having);
				if (StringUtils.isNotBlank(having)) {
					builder.append(" having ").append(having);
				}
			}
			Group<List<FieldParam>> havingGroup = groupPair.getHavingGroup();
			// TODO:
		}
		sqlWrapper.setSql(builder.toString());
		return sqlWrapper;
	}

	private <T> List<Object> buildWithParamGroup(BeanMeta<T> beanMeta, StringBuilder builder, Group<List<FieldParam>> paramGroup, Map<String, Object> paraMap) {
		List<Object> paraList = new ArrayList<>();
		paramGroup.forEach(event -> {
			if (event.isGroupStart()) {
				builder.append("(");
				return;
			}
			if (event.isGroupEnd()) {
				builder.append(")");
				return;
			}
			if (event.isGroupAnd()) {
				builder.append(" and ");
				return;
			}
			if (event.isGroupOr()) {
				builder.append(" or ");
				return;
			}
			List<FieldParam> params = event.getValue();
			for (int i = 0; i < params.size(); i++) {
				if (i == 0) {
					builder.append("(");
				} else {
					builder.append(" and (");
				}
				FieldParam param = params.get(i);
				FieldOp.OpPara opPara = new FieldOp.OpPara(
						(name) -> {
							String field = name != null ? name : param.getName();
							FieldMeta meta = beanMeta.requireFieldMeta(field);
							return resolveDbFieldSql(meta.getFieldSql(), paraMap);
						},
						param.isIgnoreCase(),
						param.getValues()
				);
				FieldOp operator = (FieldOp) param.getOperator();
				paraList.addAll(operator.operate(builder, opPara));
				builder.append(")");
			}
		});
		return paraList;
	}

	private GroupPair resolveGroupPair(Group<List<FieldParam>> paramsGroup, SqlWrapper<Object> groupBy) {
		if (groupBy == null) {
			return new GroupPair(paramsGroup, null);
		}
		String groupBySql = groupBy.getSql();
		if (paramsGroup.isRaw()) {
			List<FieldParam> where = new ArrayList<>();
			List<FieldParam> having = new ArrayList<>();
			for (FieldParam param: paramsGroup.getValue()) {
				if (StringUtils.sqlContains(groupBySql, param.getName())) {
					where.add(param);
				} else {
					having.add(param);
				}
			}
			return new GroupPair(
					where.isEmpty() ? null : new Group<>(where),
					having.isEmpty() ? null : new Group<>(having)
			);
		}
		// 复杂的组，都作为 having 条件
		return new GroupPair(null, paramsGroup);
	}

	protected static class GroupPair {

		final Group<List<FieldParam>> whereGroup;
		final Group<List<FieldParam>> havingGroup;

		public GroupPair(Group<List<FieldParam>> whereGroup, Group<List<FieldParam>> havingGroup) {
			this.whereGroup = whereGroup;
			this.havingGroup = havingGroup;
		}

		public Group<List<FieldParam>> getWhereGroup() {
			return whereGroup;
		}

		public Group<List<FieldParam>> getHavingGroup() {
			return havingGroup;
		}
	}

	protected boolean hasGroupParam(Group<List<FieldParam>> paramsGroup, SqlWrapper<Object> groupBy) {
		String groupBySql = groupBy.getSql();
		return paramsGroup.judgeAny(list -> {
			for (FieldParam param: list) {
				if (StringUtils.sqlContains(groupBySql, param.getName())) {
					return true;
				}
			}
			return false;
		});
	}

	protected <T> SqlWrapper<Object> resolveGroupBy(BeanMeta<T> beanMeta, Map<String, Object> paraMap) {
		String groupBy = beanMeta.getGroupBy();
		if (StringUtils.isNotBlank(groupBy)) {
			SqlWrapper<Object> sqlWrapper = new SqlWrapper<>();
			groupBy = buildCondition(sqlWrapper, beanMeta.getGroupBySqlParas(), paraMap, groupBy);
			if (StringUtils.isNotBlank(groupBy)) {
				sqlWrapper.setSql(groupBy);
				return sqlWrapper;
			}
		}
		return null;
	}

	protected String buildCondition(SqlWrapper<Object> sqlWrapper, List<SqlSnippet.SqlPara> sqlParas, Map<String, Object> paraMap, String condition) {
		for (SqlSnippet.SqlPara sqlPara : sqlParas) {
			Object para = paraMap.get(sqlPara.getName());
			if (sqlPara.isJdbcPara()) {
				sqlWrapper.addPara(para);
			} else {
				// 将这部分逻辑提上来，当 condition 只有一个拼接参数 且 该参数为空时，使其不参与 where 子句
				String strParam = para != null ? para.toString() : "";
				condition = condition.replace(sqlPara.getSqlName(), strParam);
			}
		}
		return condition;
	}


	protected <T> String buildClusterSql(BeanMeta<T> beanMeta, String clusterSelectSql, String fieldSelectSql, String fromWhereSql) {
		if (beanMeta.isDistinctOrGroupBy()) {
			String tableAlias = getTableAlias(beanMeta);
			return clusterSelectSql + " from (" + fieldSelectSql + fromWhereSql + ") " + tableAlias;
		}
		return clusterSelectSql + fromWhereSql;
	}

	protected <T> SqlWrapper<Object> buildListSql(BeanMeta<T> beanMeta, String fieldSelectSql, String fromWhereSql,
				List<OrderBy> orderBys, Paging paging, List<String> fetchFields, Map<String, Object> paraMap) {
		SqlSnippet orderBySnippet = beanMeta.getOrderBySnippet();
		boolean defaultOrderBy = StringUtils.isNotBlank(orderBySnippet.getSql());
		StringBuilder builder = new StringBuilder(fromWhereSql);
		int count = orderBys.size();
		if (count > 0 || defaultOrderBy) {
			builder.append(" order by ");
		}
		for (int index = 0; index < count; index++) {
			OrderBy orderBy = orderBys.get(index);
			FieldMeta meta = beanMeta.requireFieldMeta(orderBy.getSort());
			if (fetchFields.contains(meta.getName())) {
				builder.append(meta.getDbAlias());
			} else {
				builder.append(meta.getFieldSql().getSql());
			}
			String order = orderBy.getOrder();
			if (StringUtils.isNotBlank(order)) {
				builder.append(' ').append(order);
			}
			if (index < count - 1) {
				builder.append(", ");
			}
		}
		if (count == 0 && defaultOrderBy) {
			SqlWrapper<Object> dbFieldSql = resolveDbFieldSql(orderBySnippet, paraMap);
			builder.append(dbFieldSql.getSql());
			SqlWrapper<Object> sqlWrapper = forPaginate(fieldSelectSql, builder.toString(), paging);
			SqlWrapper<Object> listSql = new SqlWrapper<>(sqlWrapper.getSql());
			listSql.addParas(dbFieldSql.getParas());
			listSql.addParas(sqlWrapper.getParas());
			return listSql;
		}
		return forPaginate(fieldSelectSql, builder.toString(), paging);
	}

	protected SqlWrapper<Object> resolveTableSql(SqlSnippet tableSnippet, Map<String, Object> paraMap) {
		SqlWrapper<Object> sqlWrapper = new SqlWrapper<>();
		String tables = tableSnippet.getSql();
		List<SqlSnippet.SqlPara> params = tableSnippet.getParas();
		tables = buildCondition(sqlWrapper, params, paraMap, tables);
		sqlWrapper.setSql(tables);
		return sqlWrapper;
	}

	protected SqlWrapper<Object> resolveDbFieldSql(SqlSnippet dbFieldSnippet, Map<String, Object> paraMap) {
		String dbField = dbFieldSnippet.getSql();
		List<SqlSnippet.SqlPara> params = dbFieldSnippet.getParas();
		SqlWrapper<Object> sqlWrapper = new SqlWrapper<>();
		dbField = buildCondition(sqlWrapper, params, paraMap, dbField);
		sqlWrapper.setSql(dbField);
		return sqlWrapper;
	}

	protected <T> String getCountAlias(BeanMeta<T> beanMeta) {
		// 注意：Oracle 数据库的别名不能以下划线开头，留参 beanMeta 方便用户重写该方法
		return "s_count";
	}

	protected String getSummaryAlias(FieldMeta fieldMeta) {
		// 注意：Oracle 数据库的别名不能以下划线开头，留参 fieldMeta 方便用户重写该方法
		return fieldMeta.getDbAlias() + "_sum_";
	}

	protected <T> String getTableAlias(BeanMeta<T> beanMeta) {
		// 注意：Oracle 数据库的别名不能以下划线开头，留参 beanMeta 方便用户重写该方法
		return "t_";
	}

}
