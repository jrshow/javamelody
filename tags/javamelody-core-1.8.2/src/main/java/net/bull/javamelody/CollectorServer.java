/*
 * Copyright 2008-2009 by Emeric Vernat, Bull
 *
 *     This file is part of Java Melody.
 *
 * Java Melody is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Java Melody is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Java Melody.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.bull.javamelody;

import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;

/**
 * Collecteur de données du serveur de collecte centralisé.
 * @author Emeric Vernat
 */
class CollectorServer {
	private static final Logger LOGGER = Logger.getLogger("javamelody");

	private final Map<String, Collector> collectorsByApplication = new LinkedHashMap<String, Collector>();
	private final Map<String, List<JavaInformations>> javaInformationsByApplication = new LinkedHashMap<String, List<JavaInformations>>();

	private final Timer timer;

	/**
	 * Constructeur.
	 * @throws IOException e
	 */
	CollectorServer() throws IOException {
		super();
		this.timer = new Timer("collector", true);

		final Map<String, List<URL>> urlsByApplication = Parameters
				.getCollectorUrlsByApplications();
		LOGGER.info("applications monitorées : " + urlsByApplication.keySet());
		LOGGER.info("urls des applications monitorées : " + urlsByApplication);

		final int periodMillis = Parameters.getResolutionSeconds() * 1000;
		LOGGER.info("résolution du monitoring en secondes : " + Parameters.getResolutionSeconds());
		final TimerTask collectTask = new TimerTask() {
			/** {@inheritDoc} */
			@Override
			public void run() {
				// il ne doit pas y avoir d'erreur dans cette task
				collectWithoutErrors();
				// cette collecte ne peut interférer avec un autre thread,
				// car les compteurs sont mis à jour et utilisés par le même timer
				// et donc le même thread (les différentes tasks ne peuvent se chevaucher)
			}
		};
		// on schedule la tâche de fond,
		// avec une exécution de suite en asynchrone pour initialiser les données
		timer.schedule(collectTask, 100, periodMillis);
	}

	void collectWithoutErrors() {
		// clone pour éviter ConcurrentModificationException
		final Map<String, List<URL>> clone;
		try {
			clone = new LinkedHashMap<String, List<URL>>(Parameters
					.getCollectorUrlsByApplications());
		} catch (final IOException e) {
			LOGGER.warn(e.getMessage(), e);
			return;
		}
		for (final Map.Entry<String, List<URL>> entry : clone.entrySet()) {
			final String application = entry.getKey();
			final List<URL> urls = entry.getValue();
			try {
				collectForApplication(application, urls);
				assert collectorsByApplication.size() == javaInformationsByApplication.size();
			} catch (final Throwable e) { // NOPMD
				// si erreur sur une webapp (indisponibilité par exemple), on continue avec les autres
				// et il ne doit y avoir aucune erreur dans cette task
				LOGGER.warn(e.getMessage(), e);
			}
		}
	}

	void collectForApplication(String application, List<URL> urls) throws IOException,
			ClassNotFoundException {
		LOGGER.info("collecte pour l'application " + application + " sur " + urls);
		assert application != null;
		assert urls != null;
		final long start = System.currentTimeMillis();
		final List<JavaInformations> javaInformationsList = new ArrayList<JavaInformations>();
		Collector collector = collectorsByApplication.get(application);
		for (final URL url : urls) {
			final List<Serializable> serialized = new LabradorRetriever(url).call();
			final List<Counter> counters = new ArrayList<Counter>();
			for (final Serializable serializable : serialized) {
				if (serializable instanceof Counter) {
					final Counter counter = (Counter) serializable;
					counter.setApplication(application);
					counters.add(counter);
				} else if (serializable instanceof JavaInformations) {
					final JavaInformations newJavaInformations = (JavaInformations) serializable;
					javaInformationsList.add(newJavaInformations);
				}
			}
			if (collector == null) {
				// on initialise les collectors au fur et à mesure
				// puisqu'on ne peut pas forcément au démarrage
				// car la webapp à monitorer peut être indisponible
				collector = createCollector(application, counters);
				collectorsByApplication.put(application, collector);
			} else {
				addRequestsAndErrors(collector, counters);
			}
		}
		javaInformationsByApplication.put(application, javaInformationsList);
		collector.collectWithoutErrors(javaInformationsList);
		LOGGER.info("collecte pour l'application " + application + " effectuée en "
				+ (System.currentTimeMillis() - start) + "ms");
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("counters " + application + " : " + collector.getCounters());
			LOGGER.debug("javaInformations " + application + " : " + javaInformationsList);
		}
	}

	private void addRequestsAndErrors(Collector collector, List<Counter> counters) {
		for (final Counter newCounter : counters) {
			for (final Counter counter : collector.getCounters()) {
				if (counter.getName().equals(newCounter.getName())) {
					counter.addRequestsAndErrors(newCounter);
					break;
				}
			}
		}
	}

	private Collector createCollector(String application, List<Counter> counters) {
		final Collector collector = new Collector(application, counters, timer);
		if (Parameters.getParameter(Parameter.MAIL_SESSION) != null
				&& Parameters.getParameter(Parameter.ADMIN_EMAILS) != null) {
			scheduleReportMailForCollectorServer(application);
			LOGGER.info("Rapport hebdomadaire programmé pour l'application " + application
					+ " à destination de " + Parameters.getParameter(Parameter.ADMIN_EMAILS));
		}
		return collector;
	}

	void addCollectorApplication(String application, List<URL> urls) throws IOException,
			ClassNotFoundException {
		collectForApplication(application, urls);
		Parameters.addCollectorApplication(application, urls);
	}

	void removeCollectorApplication(String application) throws IOException {
		Parameters.removeCollectorApplication(application);
		collectorsByApplication.remove(application);
		javaInformationsByApplication.remove(application);
	}

	/**
	 * Retourne le collector pour une application à partir de son code.
	 * @param application Code de l'application
	 * @return Collector
	 */
	Collector getCollectorByApplication(String application) {
		return collectorsByApplication.get(application);
	}

	/**
	 * Retourne la liste des informations java à partir du code l'application.
	 * @param application Code de l'application
	 * @return Liste de JavaInformations
	 */
	List<JavaInformations> getJavaInformationsByApplication(String application) {
		return javaInformationsByApplication.get(application);
	}

	/**
	 * Retourne le code de la première application dans la liste
	 * @return String
	 */
	String getFirstApplication() {
		if (collectorsByApplication.isEmpty()) {
			return null;
		}
		return collectorsByApplication.keySet().iterator().next();
	}

	/**
	 * Retourne true si les données d'une application sont disponibles (c'est-à-dire si au moins
	 * une communication avec l'application a pu avoir lieu)
	 * @param application Code l'application
	 * @return boolean
	 */
	boolean isApplicationDataAvailable(String application) {
		assert application != null;
		return collectorsByApplication.containsKey(application)
				&& javaInformationsByApplication.containsKey(application);
	}

	void scheduleReportMailForCollectorServer(final String application) {
		assert application != null;
		final MailReport mailReport = new MailReport();
		final TimerTask task = new TimerTask() {
			/** {@inheritDoc} */
			@Override
			public void run() {
				try {
					// envoi du rapport
					final Collector collector = getCollectorByApplication(application);
					final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
					mailReport.sendReportMail(collector, true, javaInformationsList);
				} catch (final Throwable t) { // NOPMD
					// pas d'erreur dans cette task
					Collector.printStackTrace(t);
				}
				// on reschedule à la même heure la semaine suivante sans utiliser de période de 24h*7
				// car certains jours font 23h ou 25h et on ne veut pas introduire de décalage
				scheduleReportMailForCollectorServer(application);
			}
		};

		// schedule 1 fois la tâche
		timer.schedule(task, MailReport.getNextExecutionDate());
	}

	/**
	 * Stoppe les collectes dans ce serveur de collecte et purge les données.
	 */
	void stop() {
		for (final Collector collector : collectorsByApplication.values()) {
			collector.stop();
		}
		timer.cancel();

		// nettoyage avant le retrait de la webapp au cas où celui-ci ne suffise pas
		collectorsByApplication.clear();
		javaInformationsByApplication.clear();
	}
}