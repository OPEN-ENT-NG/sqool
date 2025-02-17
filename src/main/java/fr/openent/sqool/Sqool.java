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

package fr.openent.sqool;

import java.text.ParseException;

import io.vertx.core.Promise;
import org.entcore.common.http.BaseServer;
import org.entcore.common.validation.ValidationException;

import fr.openent.sqool.controllers.SqoolController;
import fr.openent.sqool.services.impl.DefaultSqoolService;
import fr.openent.sqool.services.impl.SyncAD;
import fr.wseduc.cron.CronTrigger;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class Sqool extends BaseServer {

	@Override
	public void start(Promise<Void> startPromise) throws Exception {
		super.start(startPromise);

		final SqoolController sqoolController = new SqoolController();
		sqoolController.setSqoolService(new DefaultSqoolService());
		addController(sqoolController);

		final JsonArray webhooks = config.getJsonArray("webhooks");
		if (webhooks != null && !webhooks.isEmpty()) {
			for (Object o: webhooks) {
				if (!(o instanceof JsonObject)) continue;
				final JsonObject webhookConfig = (JsonObject) o;
				final String syncCron = webhookConfig.getString("sync-cron");
				if (syncCron != null) {
					try {
						new CronTrigger(vertx, syncCron).schedule(new SyncAD(vertx, webhookConfig));
					} catch (ValidationException ex) {
						log.error("Invalid configuration for sync AD task", ex);
					} catch (ParseException e) {
						log.error("Error parsing quartz expression for sync AD", e);
					}
				}
			}
		}
		startPromise.tryComplete();
	}

}
