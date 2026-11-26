package org.ywzj.rvp.guidance;

import com.mojang.brigadier.StringReader;

import java.util.Locale;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/** 可热切换的数学公式，只处理三个系数和数值表达式。 */
public final class RVP_OpticalConfidenceFormula {
    /** 给 Minecraft 指令前缀保留空间，同时限制公式解析及每次计算的成本。 */
    public static final int MAX_LENGTH = 220;
    /** 默认采用两项达标并带最低项兜底线的公式。 */
    public static final RVP_OpticalConfidenceFormula DEFAULT = compile(
            "if(q < .50, min(B, .30 * (q / .50)^2), if(m < .25, min(B, .40), max(B, .41)))");

    /** 原始表达式，用于查看当前设置和向客户端同步。 */
    private final String source;
    /** 解析完毕的数值表达式，在识别时直接求值。 */
    private final Expression expression;

    private RVP_OpticalConfidenceFormula(String source, Expression expression) {
        this.source = source;
        this.expression = expression;
    }

    public static RVP_OpticalConfidenceFormula compile(String source) {
        String formula = source.trim();
        if (formula.isEmpty() || formula.length() > MAX_LENGTH)
            throw new IllegalArgumentException("公式长度必须为 1 到 " + MAX_LENGTH + " 个字符");
        var parser = new Parser(formula);
        Expression expression = parser.comparison();
        parser.reader.skipWhitespace();
        if (parser.reader.canRead()) throw parser.error("存在未识别的内容");
        return new RVP_OpticalConfidenceFormula(formula, expression);
    }

    public String source() {
        return source;
    }

    /** 完全不可见、无有效投影或未取得光照信息的对象不参与识别。 */
    public float score(float visibility, float size, float lighting) {
        if (visibility <= 0f || size <= 0f || lighting <= 0f) return 0f;
        // 用输入的十进制刻度比较 .50 和 .25 等边界；不引入 float 转 double 的小数尾差。
        var factors = new Factors(decimal(visibility), decimal(size), decimal(lighting));
        double result = expression.evaluate(factors);
        return Double.isFinite(result) ? (float) Math.min(1, Math.max(0, result)) : 0f;
    }

    /** 归一到 7 位小数，与指令输入的十进制常量比较。 */
    private static double decimal(float factor) {
        return Math.round((double) factor * 10_000_000) / 10_000_000.0;
    }

    @FunctionalInterface
    private interface Expression {
        double evaluate(Factors f);
    }

    /** V、S、L 为三个基础系数，A、m、q、B 是指令里可直接使用的中间量。 */
    private record Factors(double visibility, double size, double lighting) {
        double weighted() { return .30 * visibility + .40 * size + .30 * lighting; }
        double minimum() { return Math.min(Math.min(visibility, size), lighting); }
        double median() {
            return Math.max(Math.min(visibility, size), Math.min(Math.max(visibility, size), lighting));
        }
        double capped() {
            double m = minimum();
            return Math.min(weighted(), 2 * m - m * m);
        }
    }

    private static Expression binary(Expression left, Expression right, DoubleBinaryOperator op) {
        return f -> op.applyAsDouble(left.evaluate(f), right.evaluate(f));
    }

    private static Expression unary(Expression value, DoubleUnaryOperator op) {
        return f -> op.applyAsDouble(value.evaluate(f));
    }

    private static final class Parser {
        /** 表达式读取游标。 */
        private final StringReader reader;
        /** 限制括号与函数的嵌套深度。 */
        private int depth;

        private Parser(String source) {
            reader = new StringReader(source);
        }

        private Expression comparison() {
            Expression left = sum();
            if (take("<=")) return binary(left, sum(), (a, b) -> a <= b ? 1 : 0);
            if (take(">=")) return binary(left, sum(), (a, b) -> a >= b ? 1 : 0);
            if (take("==")) return binary(left, sum(), (a, b) -> a == b ? 1 : 0);
            if (take("!=")) return binary(left, sum(), (a, b) -> a != b ? 1 : 0);
            if (take("<")) return binary(left, sum(), (a, b) -> a < b ? 1 : 0);
            if (take(">")) return binary(left, sum(), (a, b) -> a > b ? 1 : 0);
            return left;
        }

        private Expression sum() {
            Expression result = product();
            while (true) {
                if (take("+")) result = binary(result, product(), (a, b) -> a + b);
                else if (take("-")) result = binary(result, product(), (a, b) -> a - b);
                else return result;
            }
        }

        private Expression product() {
            Expression result = signedPower();
            while (true) {
                if (take("*")) result = binary(result, signedPower(), (a, b) -> a * b);
                else if (take("/")) result = binary(result, signedPower(), (a, b) -> a / b);
                else return result;
            }
        }

        private Expression signedPower() {
            if (take("+")) return signedPower();
            if (take("-")) return unary(signedPower(), v -> -v);
            Expression value = atom();
            return take("^") ? binary(value, signedPower(), Math::pow) : value;
        }

        private Expression atom() {
            if (take("(")) {
                enter();
                Expression value = comparison();
                expect(")");
                depth--;
                return value;
            }
            if (reader.canRead() && Character.isLetter(reader.peek())) {
                int start = reader.getCursor();
                while (reader.canRead() && Character.isLetter(reader.peek())) reader.skip();
                String name = reader.getString().substring(start, reader.getCursor()).toLowerCase(Locale.ROOT);
                if (take("(")) return function(name);
                return switch (name) {
                    case "v" -> Factors::visibility;
                    case "s" -> Factors::size;
                    case "l" -> Factors::lighting;
                    case "a" -> Factors::weighted;
                    case "m" -> Factors::minimum;
                    case "q" -> Factors::median;
                    case "b" -> Factors::capped;
                    default -> throw error("未知变量 " + name);
                };
            }
            int start = reader.getCursor();
            readDigits();
            if (reader.canRead() && reader.peek() == '.') {
                reader.skip();
                readDigits();
            }
            if (reader.canRead() && (reader.peek() == 'e' || reader.peek() == 'E')) {
                reader.skip();
                if (reader.canRead() && (reader.peek() == '+' || reader.peek() == '-')) reader.skip();
                readDigits();
            }
            try {
                double constant = Double.parseDouble(reader.getString().substring(start, reader.getCursor()));
                if (Double.isFinite(constant)) return f -> constant;
            } catch (NumberFormatException invalid) {
                throw error("需要数字、变量或括号");
            }
            throw error("数字超出范围");
        }

        private void readDigits() {
            while (reader.canRead() && reader.peek() >= '0' && reader.peek() <= '9') reader.skip();
        }

        private Expression function(String name) {
            enter();
            Expression first = comparison();
            Expression result;
            if (name.equals("abs") || name.equals("sqrt")) {
                result = unary(first, name.equals("abs") ? Math::abs : Math::sqrt);
            } else {
                expect(",");
                Expression second = comparison();
                if (name.equals("min") || name.equals("max")) {
                    DoubleBinaryOperator op = name.equals("min") ? Math::min : Math::max;
                    result = binary(first, second, op);
                    if (take(",")) result = binary(result, comparison(), op);
                } else {
                    expect(",");
                    Expression third = comparison();
                    result = switch (name) {
                        case "if" -> f -> first.evaluate(f) > 0 ? second.evaluate(f) : third.evaluate(f);
                        case "clamp" -> f -> Math.max(second.evaluate(f), Math.min(third.evaluate(f), first.evaluate(f)));
                        case "median" -> f -> new Factors(first.evaluate(f), second.evaluate(f), third.evaluate(f)).median();
                        default -> throw error("未知函数 " + name);
                    };
                }
            }
            expect(")");
            depth--;
            return result;
        }

        private void enter() {
            if (++depth > 24) throw error("括号嵌套过深");
        }

        private boolean take(String token) {
            reader.skipWhitespace();
            if (!reader.getString().startsWith(token, reader.getCursor())) return false;
            reader.setCursor(reader.getCursor() + token.length());
            return true;
        }

        private void expect(String token) {
            if (!take(token)) throw error("缺少 " + token);
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("第 " + (reader.getCursor() + 1) + " 个字符附近" + message);
        }
    }
}
