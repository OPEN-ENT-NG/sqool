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
    public void export(String uai, String p, String level, Handler<Either<String, JsonArray>> handler) {
        final String profile = Utils.isNotEmpty(p) ? p : "Student";
        final JsonObject params = new JsonObject().put("UAI", uai).put("profile", profile);
        final String filter;
        if ((Utils.isNotEmpty(level) && "Student".equals(profile))) {
            filter = "AND u.level = {level} ";
            params.put("level", level);
        } else {
            filter = "";
        }
        final String query =
                "MATCH (s:Structure {UAI:{UAI}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
                "WHERE HEAD(u.profiles) = {profile} " + filter +
                "OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(c:Class)-[:BELONGS]->(sc:Structure) " +
                "OPTIONAL MATCH u-[:IN]->(:ProfileGroup)-[:DEPENDS]->(se:Structure) " +
                "OPTIONAL MATCH u-[:IN]->(fg:FunctionalGroup) " +
                "OPTIONAL MATCH u-[:IN]->(mg:ManualGroup) " +
                "RETURN u.login as login, u.lastName as lastName, u.firstName as firstName, u.displayName as username, u.birthDate as birthDate, " +
                "head(u.profiles) as type, COLLECT(DISTINCT se.UAI) as uai, u.externalId as userId, u.activationCode as activationCode, " +
                "COLLECT(DISTINCT {UAI: sc.UAI, classname: c.name}) as realClassesNames, " +
                "u.level as level, u.startDateClasses as startDateClasses, u.endDateClasses as endDateClasses, " +
                "(CASE WHEN LENGTH(COLLECT(DISTINCT fg)) = 0 THEN [] ELSE COLLECT(DISTINCT {id: fg.id, name: fg.name, source: 'AUTO'}) END + " +
                "CASE WHEN LENGTH(COLLECT(DISTINCT mg)) = 0 THEN [] ELSE COLLECT(DISTINCT {id: mg.id, name: mg.name, source: 'MANUAL'}) END) as groups;";
        neo4j.execute(query, params, Neo4jResult.validResultHandler(handler));
    }

}
