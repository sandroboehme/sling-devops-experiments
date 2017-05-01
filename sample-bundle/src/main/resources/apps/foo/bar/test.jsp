
<%@ page session="false"%>
<%@ page isELIgnored="false"%>
<%@ page import="javax.jcr.*,org.apache.sling.api.resource.Resource, org.apache.sling.api.resource.ValueMap"%>
<%@ page import="org.apache.sling.samples.test.MyService"%>
<%@ page import="java.security.Principal"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling"%>

<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>

<sling:defineObjects />

<% 
MyService myService = sling.getService(MyService.class);

Principal userPrincipal = ((HttpServletRequest)pageContext.getRequest()).getUserPrincipal();
String ready = request.getParameter("ready");
if ("admin".equals(userPrincipal.getName()) && ready != null && ready.length() > 0) {
  myService.setReady(Boolean.parseBoolean(ready));
}
if (!myService.getReady()) {
  response.setStatus(500);
}
%>
[content]: <%= currentNode.getProperty("text").getString() %>
[script] : Eins
[service]: <%= myService.getString() %>
[service - ready]: <%= myService.getReady() %>
