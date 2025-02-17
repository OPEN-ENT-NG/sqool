/*
 * Copyright © Région Ile-de-France, 2020.
 *
 * This file is part of OPEN ENT NG. OPEN ENT NG is a versatile ENT Project based on the JVM and ENT Core Project.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with OPEN ENT NG is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of OPEN ENT NG, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package fr.openent.sqool.services.impl;

import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;

import fr.openent.sqool.services.SqoolService;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.Utils;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class DefaultSqoolService implements SqoolService {

    private final Neo4j neo4j = Neo4j.getInstance();

    @Override
    public void export(String uai, String p, String level,JsonArray additionalAttributes, Handler<Either<String, JsonArray>> handler) {
        final String profile = Utils.isNotEmpty(p) ? p : "Student";
        final JsonObject params = new JsonObject().put("UAI", uai).put("profile", profile);
        final boolean includeUai = additionalAttributes.contains("includeUaiInGroups");
        final String filter;
        if ((Utils.isNotEmpty(level) && "Student".equals(profile))) {
            filter = "AND u.level = {level} ";
            params.put("level", level);
        } else {
            filter = "";
        }

        final String additionalReturn = composeAdditionalReturn(profile, additionalAttributes);

        final String query =
                "MATCH (s:Structure {UAI:{UAI}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
                "WHERE HEAD(u.profiles) = {profile} " + filter +
                "MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(sr:Structure) " +
                "OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(sc:Structure) " +
                "OPTIONAL MATCH u-[:ADMINISTRATIVE_ATTACHMENT]->(se:Structure) " +
                "OPTIONAL MATCH u-[:IN]->(fg:FunctionalGroup)-[:DEPENDS]->(fgs:Structure) " +
                "OPTIONAL MATCH u-[:IN]->(mg:ManualGroup)-[:DEPENDS]->(mgs:Structure) " +
                "OPTIONAL MATCH (p:Profile) WHERE p.name = head(u.profiles) " +
                "RETURN u.login as login, u.lastName as lastName, u.firstName as firstName, u.displayName as username, " +
                additionalReturn +
                "head(u.profiles) as type, COLLECT(DISTINCT se.UAI) as uai, COLLECT(DISTINCT sr.UAI) as uaiAttachment, u.externalId as userId, u.activationCode as activationCode, " +
                "COLLECT(DISTINCT {UAI: sc.UAI, classname: c.name}) as realClassesNames, " +
                "(CASE WHEN LENGTH(COLLECT(DISTINCT fg)) = 0 THEN [] ELSE COLLECT(DISTINCT {id: fg.id, name: fg.name, source: 'AUTO'" + (includeUai ? ", UAI: fgs.UAI" : "") +"}) END + " +
                "CASE WHEN LENGTH(COLLECT(DISTINCT mg)) = 0 THEN [] ELSE COLLECT(DISTINCT {id: mg.id, name: mg.name, source: 'MANUAL'" + (includeUai ? ", UAI: mgs.UAI" : "") +"}) END) as groups;";
        neo4j.execute(query, params, Neo4jResult.validResultHandler(handler));
    }

    private String composeAdditionalReturn(final String profile, final JsonArray additionalAttributes) {
        String additionalReturn;
        if ("Student".equals(profile)) {
            additionalReturn =
                    "u.birthDate as birthDate, u.attachmentId as attachmentId, u.module as module, u.moduleName as moduleName, " +
                            "u.level as level, u.startDateClasses as startDateClasses, u.endDateClasses as endDateClasses, ";
            if (additionalAttributes.contains("ine")) additionalReturn += "u.ine as INE, ";
        } else if ("Teacher".equals(profile) || "Personnel".equals(profile)) {
            additionalReturn = "u.emailAcademy as emailAcademy, u.modules as modules, ";
        } else {
            additionalReturn = "";
        }

        for (Object a : additionalAttributes.getList()) {
            if (a == null || !(a instanceof String)) continue;
            switch ((String) a) {
                case "ine":
                    break;
                case "includeUaiInGroups":
                    break;
                case "blocked":
                    additionalReturn += "CASE WHEN p.blocked = true OR u.blocked = true THEN true ELSE false END AS blocked, ";
                    break;
                case "deleteDate":
                    additionalReturn += "has(u.deleteDate) AS preDeleted, ";
                    break;
                default:
                    additionalReturn += "u." + a + " AS " + a + ", ";
            }
        }
        return additionalReturn;
    }
}
