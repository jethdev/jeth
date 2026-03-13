/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.contract;

import io.jeth.abi.AbiType;
import io.jeth.abi.Function;
import io.jeth.codegen.AbiJson;
import io.jeth.core.EthClient;
import io.jeth.core.EthException;
import io.jeth.crypto.Wallet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime contract proxy — give it ABI + interface, get back a typed object.
 *
 * <p>No DTOs needed. Multi-return values are mapped to auto-generated records at runtime. Single
 * returns are cast directly to the declared return type.
 *
 * <p>The interface convention:
 *
 * <ul>
 *   <li>View functions: {@code CompletableFuture<ReturnType> methodName(args...)}
 *   <li>Write functions: {@code CompletableFuture<String> methodName(Wallet wallet, args...)}
 *   <li>Payable: {@code CompletableFuture<String> methodName(Wallet wallet, BigInteger ethValue,
 *       args...)}
 * </ul>
 *
 * <pre>
 * interface Greeter {
 *     CompletableFuture&lt;String&gt;     getGreeting();
 *     CompletableFuture&lt;String&gt;     setGreeting(Wallet wallet, String greeting);
 *     CompletableFuture&lt;BigInteger&gt; greetCount();
 * }
 *
 * Greeter greeter = ContractProxy.load(Greeter.class, "0xAddress", abiJson, client);
 * String msg    = greeter.getGreeting().join();
 * String txHash = greeter.setGreeting(wallet, "hiii").join();
 * </pre>
 *
 * For multi-return functions, declare the return type as a plain interface and the proxy maps ABI
 * outputs to it by position:
 *
 * <pre>
 * interface TokenInfo {
 *     String  name();
 *     String  symbol();
 *     Integer decimals();
 * }
 *
 * interface Token {
 *     CompletableFuture&lt;TokenInfo&gt; getInfo();
 * }
 *
 * Token token = ContractProxy.load(Token.class, "0xAddress", abiJson, client);
 * TokenInfo info = token.getInfo().join();
 * System.out.println(info.name() + " " + info.symbol()); // "USD Coin USDC"
 * </pre>
 */
public class ContractProxy {

    @SuppressWarnings("unchecked")
    public static <T> T load(Class<T> iface, String address, String abiJson, EthClient client) {
        if (!iface.isInterface())
            throw new EthException("ContractProxy requires an interface, got: " + iface.getName());

        Map<String, ContractFunction> fnMap = buildFunctionMap(address, abiJson, client);

        return (T)
                Proxy.newProxyInstance(
                        iface.getClassLoader(), new Class[] {iface}, new Handler(fnMap, iface));
    }

    // ─── Build function map ───────────────────────────────────────────────────

    private static Map<String, ContractFunction> buildFunctionMap(
            String address, String abiJson, EthClient client) {

        Map<String, ContractFunction> map = new LinkedHashMap<>();

        for (AbiJson.Entry entry : AbiJson.parse(abiJson)) {
            if (!entry.isFunction()) continue;

            List<AbiJson.Param> inputs = entry.inputs != null ? entry.inputs : List.of();
            List<AbiJson.Param> outputs = entry.outputs != null ? entry.outputs : List.of();

            AbiType[] inTypes =
                    inputs.stream().map(p -> AbiType.of(p.canonicalType())).toArray(AbiType[]::new);
            AbiType[] outTypes =
                    outputs.stream()
                            .map(p -> AbiType.of(p.canonicalType()))
                            .toArray(AbiType[]::new);

            Function fn = Function.of(entry.name, inTypes);
            if (outTypes.length > 0) fn = fn.withReturns(outTypes);

            ContractFunction cf = new ContractFunction(address, client, fn);
            map.put(entry.name, cf);
            map.put(fn.getSignature(), cf);
        }
        return map;
    }

    // ─── Invocation handler ───────────────────────────────────────────────────

    private static class Handler implements InvocationHandler {

        private final Map<String, ContractFunction> fnMap;
        private final Class<?> iface;

        Handler(Map<String, ContractFunction> fnMap, Class<?> iface) {
            this.fnMap = fnMap;
            this.iface = iface;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] rawArgs) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "ContractProxy[" + iface.getSimpleName() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == rawArgs[0];
                    default -> method.invoke(this, rawArgs);
                };
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, rawArgs);

            Object[] args = rawArgs != null ? rawArgs : new Object[0];

            ContractFunction cf = fnMap.get(method.getName());
            if (cf == null)
                throw new EthException(
                        "No ABI entry for: '"
                                + method.getName()
                                + "'. Available: "
                                + fnMap.keySet());

            // Split wallet / ethValue / contract args
            Wallet wallet = null;
            BigInteger ethValue = BigInteger.ZERO;
            int argStart = 0;

            if (args.length > 0 && args[0] instanceof Wallet w) {
                wallet = w;
                argStart = 1;
                if (args.length > 1 && args[1] instanceof BigInteger bi && isPayable(method)) {
                    ethValue = bi;
                    argStart = 2;
                }
            }

            Object[] contractArgs = Arrays.copyOfRange(args, argStart, args.length);

            if (wallet != null) {
                return cf.send(wallet, ethValue, contractArgs);
            }

            // Read — map result to declared return type
            ContractFunction.CallResult result = cf.call(contractArgs);
            return mapResult(result, method);
        }

        /**
         * Maps the raw Object[] from ABI decoding to whatever the interface method declares.
         *
         * <p>Rules: - CompletableFuture<String>, <BigInteger>, <Boolean>, <Integer> etc → direct
         * cast - CompletableFuture<SomeInterface> → auto-proxy: map outputs by position to
         * interface methods - CompletableFuture<Object[]> → raw array pass-through
         */
        private Object mapResult(ContractFunction.CallResult result, Method method) {
            Type returnType = method.getGenericReturnType();
            if (!(returnType instanceof ParameterizedType pt)) return result.raw();

            Type typeArg = pt.getActualTypeArguments()[0];

            // Multi-return interface (struct-like)
            if (typeArg instanceof Class<?> target && target.isInterface()) {
                return result.raw().thenApply(arr -> proxyStruct(target, arr));
            }

            // Object[] pass-through
            if (typeArg == Object[].class) return result.raw();

            // Single value — cast
            Class<?> target = rawClass(typeArg);
            return result.as(target);
        }

        /**
         * Creates a proxy for a struct-like interface backed by an Object[] from ABI decoding.
         * Methods are matched to output positions by name (ABI param name) or by index order.
         */
        @SuppressWarnings("unchecked")
        private static <T> T proxyStruct(Class<T> iface, Object[] values) {
            Method[] methods = iface.getMethods();

            return (T)
                    Proxy.newProxyInstance(
                            iface.getClassLoader(),
                            new Class[] {iface},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class) {
                                    if ("toString".equals(method.getName())) {
                                        return buildStructToString(iface, methods, values);
                                    }
                                    return method.invoke(proxy, args);
                                }

                                // Match method to its positional index in the interface's method
                                // list
                                for (int i = 0; i < methods.length; i++) {
                                    if (methods[i].getName().equals(method.getName())) {
                                        if (i < values.length)
                                            return coerce(values[i], method.getReturnType());
                                        throw new EthException(
                                                "ABI output index "
                                                        + i
                                                        + " out of bounds ("
                                                        + values.length
                                                        + " values) for: "
                                                        + method.getName());
                                    }
                                }
                                throw new EthException(
                                        "No ABI output mapped to: " + method.getName());
                            });
        }

        private static String buildStructToString(
                Class<?> iface, Method[] methods, Object[] values) {
            StringBuilder sb = new StringBuilder(iface.getSimpleName()).append("{");
            for (int i = 0; i < methods.length && i < values.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(methods[i].getName()).append("=").append(values[i]);
            }
            return sb.append("}").toString();
        }

        /** Coerce a decoded ABI value (usually BigInteger) to the declared Java type. */
        private static Object coerce(Object value, Class<?> target) {
            if (value == null) return null; // contract returned empty / null ABI output
            if (target.isInstance(value)) return value;
            if (value instanceof BigInteger bi) {
                if (target == int.class || target == Integer.class) return bi.intValue();
                if (target == long.class || target == Long.class) return bi.longValue();
                if (target == boolean.class || target == Boolean.class)
                    return bi.compareTo(BigInteger.ZERO) != 0;
                if (target == String.class) return bi.toString();
            }
            if (value instanceof Boolean b && (target == boolean.class)) return b;
            return value;
        }

        private static boolean isPayable(Method method) {
            Parameter[] p = method.getParameters();
            return p.length >= 2
                    && p[0].getType() == Wallet.class
                    && p[1].getType() == BigInteger.class;
        }

        private static Class<?> rawClass(Type type) {
            if (type instanceof Class<?> c) return c;
            if (type instanceof ParameterizedType pt) return rawClass(pt.getRawType());
            return Object.class;
        }
    }
}
