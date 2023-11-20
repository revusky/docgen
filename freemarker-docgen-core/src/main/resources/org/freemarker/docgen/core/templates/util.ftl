<#ftl nsPrefixes={"D":"http://docbook.org/ns/docbook"}>
<#--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at
  
    http://www.apache.org/licenses/LICENSE-2.0
  
  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

<#function getOptionalTitleAsString node>
  <#return titleToString(getOptionalTitleElement(node))>
</#function>

<#function getOptionalTitleElement node preferTitleAbbrev=false>
  <#if preferTitleAbbrev>
    <#local result = node.info.titleabbrev>
  </#if>
  <#if !result??><#local result = node.title></#if>
  <#if !result??><#local result = node.info.title></#if>
  <#if !result??>
     <#return ''>
  </#if>
  <#return result>
</#function>

<#function getRequiredTitleElement node preferTitleAbbrev=false>
  <#local result = getOptionalTitleElement(node, preferTitleAbbrev)>
  <#if !result??>
    <#stop "Required \"title\" child element missing for element \""
        + node?node_name + "\".">
  </#if>
  <#return result>
</#function>

<#function getRequiredTitleAsString node>
  <#return titleToString(getRequiredTitleElement(node))>
</#function>

<#function getOptionalSubtitleElement node>
  <#local result = node.subtitle>
  <#if !result??><#local result = node.info.subtitle></#if>
  <#if !result??>
    <#return ''>
  </#if>
  <#return result>
</#function>

<#function getOptionalSubtitleAsString node>
  <#return titleToString(getOptionalSubtitleElement(node))>
</#function>

<#function titleToString titleNode>
  <#if !titleNode??>
    <#-- Used for optional title -->
    <#return ''>
  </#if>
  <#if !titleNode?is_node>
    <#-- Just a string... -->
    <#return titleNode>
  </#if>

  <#local res = "">
  <#list titleNode?children as child>
    <#if child?node_type == "text">
      <#local res = res+child>
    <#elseif child?node_type == "element">
      <#local name = child?node_name>
      <#if ["literal", "classname", "methodname", "package", "replaceable", "emphasis", "phrase",
            "olink", "link"]?seq_contains(name)>
        <#local res = res + titleToString(child)>
      <#elseif name == "quote">
        <#local res = "\x201C" + titleToString(child) + "\x201D">
      <#elseif name != "subtitle">
        <#stop 'The "${name}" in titles is not supported by Docgen.'>
      </#if>
    </#if>
  </#list>

  <#return res>
</#function>

<#-- "docStructElem" is a part, chapter, section, etc., NOT a title element -->
<#function getTitlePrefix docStructElem, extraSpacing=false, longForm=false>
  <#local prefix = docStructElem.@docgen_title_prefix[0]!>
  <#if !prefix??>
    <#return "">
  </#if>

  <#local type = docStructElem?node_name>

  <#local spacer = ": ">


  <#if type == "chapter">
    <#return longForm?string("Chapter ", "") + prefix + spacer>
  <#elseif type == "appendix">
    <#return longForm?string("Appendix ", "") + prefix + spacer>
  <#elseif type == "part">
    <#return longForm?string("Part ", "") + prefix + spacer>
  <#elseif type == "article">
    <#return longForm?string("Article ", "") + prefix + spacer>
  <#else>
    <#return prefix + spacer>
  </#if>
</#function>

<#macro invisible1x1Img>
  <img src="docgen-resources/img/none.gif" width="1" height="1" alt="" hspace="0" vspace="0" border="0"/><#t>
</#macro>
