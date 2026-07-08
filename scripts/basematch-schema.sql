-- MySQL dump 10.13  Distrib 8.0.46, for Linux (x86_64)
--
-- Host: 127.0.0.1    Database: repatch2_full2mech_20260704
-- ------------------------------------------------------
-- Server version	8.0.46-0ubuntu0.24.04.3

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `conflict_block`
--

DROP TABLE IF EXISTS `conflict_block`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conflict_block` (
  `id` int NOT NULL AUTO_INCREMENT,
  `path` varchar(1000) NOT NULL,
  `conflicting_loc` int NOT NULL,
  `start_line` int NOT NULL,
  `end_line` int NOT NULL,
  `merge_tool` varchar(20) NOT NULL,
  `is_same` tinyint NOT NULL,
  `is_comment` tinyint NOT NULL,
  `conflicting_file_id` int NOT NULL,
  `merge_result_id` int NOT NULL,
  `merge_commit_id` int NOT NULL,
  `project_id` int NOT NULL,
  `patch_id` int NOT NULL,
  PRIMARY KEY (`id`,`merge_result_id`,`merge_commit_id`,`project_id`,`conflicting_file_id`,`patch_id`),
  KEY `fk_conflict_block_conflicting_file1_idx` (`conflicting_file_id`,`merge_result_id`,`merge_commit_id`,`project_id`,`patch_id`),
  CONSTRAINT `fk_conflict_block_conflicting_file1` FOREIGN KEY (`conflicting_file_id`, `merge_result_id`, `merge_commit_id`, `project_id`, `patch_id`) REFERENCES `conflicting_file` (`id`, `merge_result_id`, `merge_commit_id`, `project_id`, `patch_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=2035 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `conflicting_file`
--

DROP TABLE IF EXISTS `conflicting_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conflicting_file` (
  `id` int NOT NULL AUTO_INCREMENT,
  `merge_tool` varchar(45) NOT NULL,
  `path` varchar(1000) NOT NULL,
  `total_conflicts` int NOT NULL,
  `total_conflicting_loc` int NOT NULL,
  `merge_result_id` int NOT NULL,
  `merge_commit_id` int NOT NULL,
  `project_id` int NOT NULL,
  `patch_id` int NOT NULL,
  PRIMARY KEY (`id`,`merge_result_id`,`merge_commit_id`,`project_id`,`patch_id`),
  KEY `fk_conflicting_file_merge_result1_idx` (`merge_result_id`,`merge_commit_id`,`project_id`,`patch_id`),
  CONSTRAINT `fk_conflicting_file_merge_result1` FOREIGN KEY (`merge_result_id`, `merge_commit_id`, `project_id`, `patch_id`) REFERENCES `merge_result` (`id`, `merge_commit_id`, `project_id`, `patch_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=969 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `failure_event`
--

DROP TABLE IF EXISTS `failure_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `failure_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` int DEFAULT NULL,
  `patch_id` int DEFAULT NULL,
  `merge_commit_id` int DEFAULT NULL,
  `operation_id` varchar(32) DEFAULT NULL,
  `phase` varchar(32) NOT NULL,
  `refactoring_type` varchar(64) DEFAULT NULL,
  `category` varchar(48) NOT NULL,
  `evidence` text,
  `is_pipeline_artifact` tinyint(1) DEFAULT '0',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3497 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merge_commit`
--

DROP TABLE IF EXISTS `merge_commit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merge_commit` (
  `id` int NOT NULL AUTO_INCREMENT,
  `commit_hash` char(40) NOT NULL,
  `is_conflicting` tinyint(1) NOT NULL,
  `parent_1` char(40) NOT NULL,
  `parent_2` char(40) NOT NULL,
  `project_id` int NOT NULL,
  `patch_id` int NOT NULL,
  `is_done` tinyint(1) DEFAULT '0',
  `author_name` varchar(150) DEFAULT NULL,
  `author_email` varchar(150) DEFAULT NULL,
  `timestamp` mediumtext,
  PRIMARY KEY (`id`,`project_id`,`patch_id`),
  UNIQUE KEY `commit_hash_UNIQUE` (`commit_hash`),
  KEY `fk_merge_commit_patch_idx` (`project_id`),
  KEY `fk_merge_commit_patch` (`project_id`,`patch_id`),
  CONSTRAINT `fk_merge_commit_patch` FOREIGN KEY (`project_id`, `patch_id`) REFERENCES `patch` (`project_id`, `id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=348 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merge_result`
--

DROP TABLE IF EXISTS `merge_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merge_result` (
  `id` int NOT NULL AUTO_INCREMENT,
  `merge_tool` varchar(45) NOT NULL,
  `total_conflicting_files` int NOT NULL,
  `total_conflicts` int NOT NULL,
  `total_conflicting_loc` int NOT NULL,
  `runtime` int NOT NULL,
  `merge_commit_id` int NOT NULL,
  `project_id` int NOT NULL,
  `patch_id` int NOT NULL,
  PRIMARY KEY (`id`,`merge_commit_id`,`project_id`,`patch_id`),
  KEY `fk_merged_result_merge_commit1_idx` (`merge_commit_id`,`project_id`,`patch_id`),
  CONSTRAINT `fk_conflicting_java_file_merge_commit1` FOREIGN KEY (`merge_commit_id`, `project_id`, `patch_id`) REFERENCES `merge_commit` (`id`, `project_id`, `patch_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=695 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `patch`
--

DROP TABLE IF EXISTS `patch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `patch` (
  `id` int NOT NULL AUTO_INCREMENT,
  `number` int NOT NULL,
  `project_id` int NOT NULL,
  `patch_type` varchar(10) DEFAULT NULL,
  `is_conflicting` tinyint(1) DEFAULT '0',
  `is_done` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`,`project_id`),
  UNIQUE KEY `number_UNIQUE` (`number`),
  UNIQUE KEY `project_id` (`project_id`,`id`),
  KEY `fk_patches_project_idx` (`project_id`),
  CONSTRAINT `fk_patches_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=461 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `project`
--

DROP TABLE IF EXISTS `project`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `project` (
  `id` int NOT NULL AUTO_INCREMENT,
  `source_url` varchar(2000) NOT NULL,
  `fork_url` varchar(2000) NOT NULL,
  `source_name` varchar(100) DEFAULT NULL,
  `fork_name` varchar(100) DEFAULT NULL,
  `is_done` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `refactoring`
--

DROP TABLE IF EXISTS `refactoring`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refactoring` (
  `id` int NOT NULL AUTO_INCREMENT,
  `refactoring_type` varchar(100) DEFAULT NULL,
  `refactoring_detail` varchar(2000) DEFAULT NULL,
  `merge_commit_id` int NOT NULL,
  `project_id` int NOT NULL,
  `patch_id` int NOT NULL,
  PRIMARY KEY (`id`,`merge_commit_id`,`project_id`,`patch_id`),
  KEY `fk_refactoring_merge_commit1_idx` (`merge_commit_id`,`project_id`,`patch_id`),
  CONSTRAINT `fk_refactoring_merge_commit1` FOREIGN KEY (`merge_commit_id`, `project_id`, `patch_id`) REFERENCES `merge_commit` (`id`, `project_id`, `patch_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=288070 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-07-06 14:00:16
