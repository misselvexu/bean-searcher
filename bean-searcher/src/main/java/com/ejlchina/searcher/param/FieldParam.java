package com.ejlchina.searcher.param;

import com.ejlchina.searcher.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 字段参数
 * @author Troy.Zhou @ 2017-03-20
 */
public class FieldParam {

	/**
	 * 字段名
	 */
	private final String name;

	/**
	 * 字段运算符
	 */
	private final Operator operator;

	/**
	 * 参数值
	 */
	private final List<Value> values = new ArrayList<>(2);

	/**
	 * 是否忽略大小写
	 */
	private boolean ignoreCase;

	/**
	 * 字段参数值
	 */
	public static class Value {

		private final Object value;
		private final int index;

		public Value(Object value, int index) {
			this.value = value;
			this.index = index;
		}

		public boolean isEmptyValue() {
			return value == null || (value instanceof String && StringUtils.isBlank((String) value));
		}

		public Object getValue() {
			return value;
		}

	}

	public FieldParam(String name, Operator operator) {
		this.name = name;
		this.operator = operator;
	}

	public String getName() {
		return name;
	}
	
	public void addValue(Object value, int index) {
		values.add(new Value(value, index));
	}

	public Object[] getValues() {
		values.sort(Comparator.comparingInt(v -> v.index));
		Object[] objects = new Object[values.size()];
		for (int i = 0; i < values.size(); i++) {
			objects[i] = values.get(i).value;
		}
		return objects;
	}

	public boolean isIgnoreCase() {
		return ignoreCase;
	}

	public void setIgnoreCase(boolean ignoreCase) {
		this.ignoreCase = ignoreCase;
	}

	public Operator getOperator() {
		return operator;
	}

	public boolean allValuesEmpty() {
		for (Value value : values) {
			if (!value.isEmptyValue()) {
				return false;
			}
		}
		return true;
	}

}