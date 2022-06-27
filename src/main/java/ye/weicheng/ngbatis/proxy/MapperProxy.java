// Copyright 2022-present Weicheng Ye. All rights reserved.
// Use of this source code is governed by a MIT-style license that can be
// found in the LICENSE file.
package ye.weicheng.ngbatis.proxy;

import com.alibaba.fastjson.JSON;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.net.Session;
import ye.weicheng.ngbatis.ArgNameFormatter;
import ye.weicheng.ngbatis.Env;
import ye.weicheng.ngbatis.ResultResolver;
import ye.weicheng.ngbatis.annotations.UseKeyArgReplace;
import ye.weicheng.ngbatis.config.ParseCfgProps;
import ye.weicheng.ngbatis.exception.QueryException;
import ye.weicheng.ngbatis.models.ClassModel;
import ye.weicheng.ngbatis.models.MapperContext;
import ye.weicheng.ngbatis.models.MethodModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static ye.weicheng.ngbatis.models.ClassModel.PROXY_SUFFIX;


/**
 * 被动态代理类所调用。用于实际的数据库访问并调用结果集处理方法
 *
 * @author yeweicheng
 * <br>Now is history!
 */
public class MapperProxy {

    private static Logger log = LoggerFactory.getLogger( MapperProxy.class );
    @Autowired
    private ParseCfgProps props;

    public static Env ENV;

    private ClassModel classModel;

    private Map<Method, MethodModel> methodCache = new HashMap<>();


    public MapperProxy(ClassModel classModel) {
        this.classModel = classModel;
        methods( classModel );
    }

    private void methods( ClassModel classModel ) {
        methodCache.clear();
        Method[] declaredMethods = classModel.getNamespace().getDeclaredMethods();
        Map<String, MethodModel> methods = classModel.getMethods();
        for( Method method : declaredMethods ) {
            methodCache.put( method, methods.get( method.getName() ) );
        }
    };

    /**
     * 提供给代理类所调用
     * @param className
     * @param methodName
     * @param args
     * @return
     */
    public static Object invoke(String className, String methodName, Object... args ) {
        MapperContext mapperContext = ENV.getMapperContext();
        String proxyClassName = className + PROXY_SUFFIX;
        ClassModel classModel = mapperContext.getInterfaces().get(proxyClassName);
        Method method = null;
        if( mapperContext.isResourceRefresh()) {
            try {
                Map<String, ClassModel> classModelMap = classModel.getResourceLoader().parseClassModel(classModel.getResource());
                classModel = classModelMap.get(proxyClassName);
                method = classModel.getMethod(methodName).getMethod();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            method = classModel.getMethod(methodName).getMethod();
        }
        MapperProxy mapperProxy = new MapperProxy(classModel);
        return mapperProxy.invoke( method, args );
    }

    /**
     * 提供给基类所调用
     *
     * @param methodModel
     * @param args
     * @return
     */
    public static Object invoke(MethodModel methodModel, Object... args) {
        Method method = methodModel.getMethod();
        ResultSet query = null;
        // 参数格式转换
        Map<String, Object> argMap = ENV.getArgsResolver().resolve( methodModel, args );
        // beetl 渲染模板
        String textTpl = methodModel.getText();
        String nGQL = ENV.getTextResolver().resolve( textTpl, argMap );
        Map<String, Object> params = null;
        if( method.isAnnotationPresent( UseKeyArgReplace.class ) ) {
            ArgNameFormatter.CqlAndArgs format = ENV.getArgNameFormatter().format(nGQL, argMap);
            nGQL = format.getCql();
            params = format.getArgs();
        } else {
            params = argMap;
        }
        query = executeWithParameter( nGQL, params );
        if (!query.isSucceeded()) {
            throw new QueryException( "数据查询失败：" + query.getErrorMessage() );
        }

        if(methodModel.getResultType() == ResultSet.class) return query;

        ResultResolver resultResolver = ENV.getResultResolver();
        Object resolve = resultResolver.resolve(methodModel, query);
        return resolve;
    }

    public Object invoke(Method method, Object... args)  {
        MethodModel methodModel = methodCache.get(method);
        methodModel.setMethod( method );

        return invoke(methodModel, args);
    }

    public static  ResultSet executeWithParameter( String nGQL, Map<String, Object> params )  {
        Session session = null;
        try {
            nGQL = "USE " + ENV.getSpace()+";" + nGQL.trim();
            session = ENV.openSession();
            log.debug("nGql：{}", nGQL);
            log.debug("params：{}", JSON.toJSONString( params ));
            ResultSet result = session.executeWithParameter( nGQL, params );
            log.debug("result：{}", result);
            if( result.isSucceeded() ) {
                return result;
            } else {
                throw new QueryException( " 数据查询失败" + result.getErrorMessage() );
            }
        } catch (IOErrorException e) {
            throw new QueryException(  "数据查询失败："  + e.getMessage() );
        } finally {
//            if (session != null ) session.release();
        }
    }

    public static Logger getLog() {
        return log;
    }

    public static void setLog(Logger log) {
        MapperProxy.log = log;
    }

    public ParseCfgProps getProps() {
        return props;
    }

    public void setProps(ParseCfgProps props) {
        this.props = props;
    }

    public ClassModel getClassModel() {
        return classModel;
    }

    public void setClassModel(ClassModel classModel) {
        this.classModel = classModel;
    }

    public Map<Method, MethodModel> getMethodCache() {
        return methodCache;
    }

    public void setMethodCache(Map<Method, MethodModel> methodCache) {
        this.methodCache = methodCache;
    }
}
